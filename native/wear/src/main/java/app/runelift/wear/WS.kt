package app.runelift.wear

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/* État partagé de la montre : la séance envoyée par le téléphone + ce qu'on a coché ici. */
object WS {
    var st: JSONObject? = null
    val pending = HashMap<String, Long>()
    var restEnd = 0L
    var restTot = 0
    var restBuzzed = 0L
    var bpm = 0
    var bpmT = 0L
    var exSel = -1
    val edits = HashMap<String, DoubleArray>()
    var lastState = 0L
    private val ui = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun onUi(f: () -> Unit) { ui.add(f) }
    fun offUi(f: () -> Unit) { ui.remove(f) }
    fun changed() { main.post { ui.toList().forEach { it() } } }
    fun on() = st?.optInt("on", 0) == 1
    fun en() = st?.optString("lang") == "en"
    fun t(fr: String, en: String) = if (en()) en else fr

    fun ex(e: Int): JSONObject? = st?.optJSONArray("ex")?.optJSONObject(e)
    fun nEx() = st?.optJSONArray("ex")?.length() ?: 0
    fun nSets(e: Int) = ex(e)?.optJSONArray("s")?.length() ?: 0
    fun set(e: Int, s: Int): JSONObject? = ex(e)?.optJSONArray("s")?.optJSONObject(s)
    fun isDone(e: Int, s: Int) = set(e, s)?.optInt("d", 0) == 1 || pending.containsKey("$e:$s")
    fun doneCount(): Pair<Int, Int> { var d = 0; var n = 0; for (e in 0 until nEx()) for (s in 0 until nSets(e)) { n++; if (isDone(e, s)) d++ }; return d to n }

    /* Série en cours : la première non cochée, en partant de l'exercice choisi. */
    fun cur(): Pair<Int, Int>? {
        val n = nEx(); if (n == 0) return null
        val order = if (exSel in 0 until n) (exSel until n) + (0 until exSel) else (0 until n)
        for (e in order) for (s in 0 until nSets(e)) if (!isDone(e, s)) return e to s
        return null
    }

    fun update(j: String) {
        val o = try { JSONObject(j) } catch (e: Exception) { return }
        val old = st
        st = o
        val now = System.currentTimeMillis()
        lastState = now
        if (old == null || old.optString("id") != o.optString("id")) { exSel = -1; pending.clear(); edits.clear() }
        pending.entries.removeIf { (k, t0) ->
            val p = k.split(":"); val e = p[0].toInt(); val s = p[1].toInt()
            set(e, s)?.optInt("d", 0) == 1 || now - t0 > 30000
        }
        val re = o.optLong("re", 0)
        if (re > now) { restEnd = re; restTot = o.optInt("rt", restTot) }
        else if (pending.isEmpty() && restEnd > now) restEnd = 0
        if (!on()) { restEnd = 0; pending.clear(); exSel = -1; edits.clear() }
        changed()
    }

    fun load(ctx: Context) {
        Wearable.getDataClient(ctx).dataItems.addOnSuccessListener { buf ->
            var j: String? = null
            for (it in buf) if (it.uri.path == "/rl/state") j = DataMapItem.fromDataItem(it).dataMap.getString("j")
            buf.release()
            if (j != null) update(j)
        }
    }

    fun send(ctx: Context, body: JSONObject) {
        val data = body.toString().toByteArray(Charsets.UTF_8)
        val app = ctx.applicationContext
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, "/rl/cmd", data) }
        }
    }

    /* Charge et répétitions affichées : celles modifiées sur la montre, sinon celles du téléphone. */
    fun w(e: Int, s: Int): Double = edits["$e:$s"]?.get(0) ?: (set(e, s)?.opt("w") as? Number)?.toDouble() ?: set(e, s)?.optString("w")?.toDoubleOrNull() ?: 0.0
    fun r(e: Int, s: Int): Double = edits["$e:$s"]?.get(1) ?: set(e, s)?.optDouble("r", 0.0) ?: 0.0
    fun hasW(e: Int, s: Int) = edits.containsKey("$e:$s") || (set(e, s)?.optString("w") ?: "").isNotEmpty()
    fun step() = 0.5
    fun adjust(e: Int, s: Int, field: Int, d: Double, quiet: Boolean = false) {
        val a = doubleArrayOf(w(e, s), r(e, s))
        a[field] = if (field == 0) maxOf(0.0, Math.round((a[field] + d * step()) * 2) / 2.0) else maxOf(1.0, a[field] + d)
        edits["$e:$s"] = a
        if (!quiet) changed()
    }

    /* Couleur principale choisie sur le téléphone (thème Runelift). */
    fun acc(): Int {
        val a = (st?.optString("acc") ?: "").trim()
        try {
            if (a.startsWith("#")) {
                val h = if (a.length == 4) "#" + a[1] + a[1] + a[2] + a[2] + a[3] + a[3] else a
                return android.graphics.Color.parseColor(h)
            }
            val m = Regex("rgba?\\(([^)]+)\\)").find(a)
            if (m != null) { val p = m.groupValues[1].split(",").map { it.trim().toFloat().toInt() }; return android.graphics.Color.rgb(p[0], p[1], p[2]) }
        } catch (e: Exception) {}
        return android.graphics.Color.parseColor("#C6F135")
    }
    fun onAcc(): Int { val c = acc(); val l = 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c); return if (l > 150) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    fun elapsedMs(): Long = try { System.currentTimeMillis() - java.time.Instant.parse(st?.optString("st")).toEpochMilli() } catch (e: Exception) { 0L }
    fun kcal(): Int = (5.0 * (st?.optInt("kg", 75) ?: 75) * elapsedMs() / 3600000.0).toInt()
    fun cmd(ctx: Context, a: String, e: Int = -1, s: Int = -1, t: String? = null) {
        val m = JSONObject().put("a", a); if (e >= 0) m.put("e", e); if (s >= 0) m.put("s", s); if (t != null) m.put("t", t)
        send(ctx, m)
    }

    fun start(ctx: Context, rid: String) { send(ctx, JSONObject().put("a", "start").put("rid", rid)) }
    fun finish(ctx: Context) { send(ctx, JSONObject().put("a", "finish")) }

    fun done(ctx: Context, e: Int, s: Int) {
        val now = System.currentTimeMillis()
        pending["$e:$s"] = now
        val m = JSONObject().put("a", "done").put("e", e).put("s", s)
        edits["$e:$s"]?.let { m.put("w", it[0]).put("r", it[1].toInt()) }
        edits.remove("$e:$s")
        send(ctx, m)
        val x = ex(e)
        val r = x?.optInt("rest", 90) ?: 90
        if (r > 0 && x?.optInt("c", 0) != 1) { restTot = r; restEnd = now + r * 1000L }
        if (exSel == e && (0 until nSets(e)).all { isDone(e, it) }) exSel = -1
        changed()
    }

    fun restAdd(ctx: Context) {
        val now = System.currentTimeMillis()
        if (restEnd > now) { restEnd += 30000; restTot += 30; send(ctx, JSONObject().put("a", "rest").put("add", 30)) }
        changed()
    }

    fun restSkip(ctx: Context) {
        restEnd = 0
        send(ctx, JSONObject().put("a", "rest").put("skip", 1))
        changed()
    }

    fun checkRest(ctx: Context) {
        val now = System.currentTimeMillis()
        if (restEnd > 0 && now >= restEnd && restBuzzed != restEnd) {
            restBuzzed = restEnd
            buzz(ctx, longArrayOf(0, 350, 180, 350, 180, 700))
            changed()
        }
    }

    @Suppress("DEPRECATION")
    fun buzz(ctx: Context, p: LongArray) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1)) else v.vibrate(p, -1)
        } catch (e: Exception) {}
    }

    fun num(v: Any?): String {
        val d = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: return ""
        return if (d % 1.0 == 0.0) d.toLong().toString() else String.format(java.util.Locale.US, "%.1f", d)
    }
}
