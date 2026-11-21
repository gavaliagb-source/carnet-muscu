package app.runelift.wear

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject

/* État partagé de la montre.
   - phone : la séance (ou les routines) envoyée par le téléphone.
   - local : une séance faite sur la montre seule (téléphone loin ou appli fermée), gardée sur la montre
     puis envoyée au téléphone à la fin. */
object WS {
    private var phone: JSONObject? = null
    private var local: JSONObject? = null
    val st: JSONObject? get() = local ?: phone
    val pending = HashMap<String, Long>()
    var restEnd = 0L
    var restTot = 0
    var restBuzzed = 0L
    var bpm = 0
    var bpmT = 0L
    var exSel = -1
    var selE = -1; var selS = -1 // série choisie avec les flèches (prioritaire tant que non cochée)
    val edits = HashMap<String, DoubleArray>()
    var lastState = 0L
    @Volatile var ver = 0          // change à chaque modification de la séance (évite de tout recalculer)
    private var lastFlush = 0L
    private var hrl = JSONArray()
    private var startWait: String? = null
    private var startTag = 0L
    private var endedId: String? = null   // séance du téléphone terminée/abandonnée depuis la montre
    private var app: Context? = null
    private val ui = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun isLocal() = local != null

    /* Mode démo (tests sur émulateur seulement : lancé avec l'option rl_demo). */
    fun demo(ctx: Context) { init(ctx); local = null; saveLocal(); update(Demo.JSON) }

    /* À appeler au démarrage de chaque composant : récupère la séance locale et le dernier état du téléphone. */
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val p = prefs()
        try { p.getString("local", null)?.let { local = JSONObject(it) } } catch (e: Exception) {}
        try { p.getString("hrl", null)?.let { hrl = JSONArray(it) } } catch (e: Exception) {}
        try { if (phone == null) p.getString("phone", null)?.let { phone = JSONObject(it) } } catch (e: Exception) {}
    }
    private fun prefs() = app!!.getSharedPreferences("rl_ws", Context.MODE_PRIVATE)
    private fun saveLocal() {
        ver++
        val a = app ?: return
        a.getSharedPreferences("rl_ws", Context.MODE_PRIVATE).edit()
            .putString("local", local?.toString()).putString("hrl", hrl.toString()).apply()
    }

    fun onUi(f: () -> Unit) { ui.add(f) }
    fun offUi(f: () -> Unit) { ui.remove(f) }
    private var lastTile = 0L
    fun changed() {
        main.post { ui.toList().forEach { it() } }
        val c = app ?: return
        val now = System.currentTimeMillis()
        if (now - lastTile > 5000) { lastTile = now; RLTile.refresh(c) }
    }
    fun on() = if (local != null) true else phoneOn()
    fun phoneOn() = phone?.optInt("on", 0) == 1 && (endedId == null || phone?.optString("id") != endedId)
    fun en() = st?.optString("lang") == "en"
    fun t(fr: String, en: String) = if (en()) en else fr
    fun routines(): JSONArray? = phone?.optJSONArray("rts")
    fun mine(): JSONArray? = phone?.optJSONArray("mine")

    /* Remplacer un exercice (machine prise, etc.). */
    /* Tes exercices (pour « Remplacer ») : liste envoyée par le téléphone, sinon ceux de tes routines. */
    fun mineList(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(); val seen = HashSet<String>()
        fun add(n: String, i: String) { if (n.isNotEmpty() && seen.add(n.lowercase())) out.add(n to i) }
        val m = mine()
        if (m != null) for (k in 0 until m.length()) { val o = m.optJSONObject(k); if (o != null) add(o.optString("n"), o.optString("i")) else add(m.optString(k), imgOf(m.optString(k))) }
        val rts = routines()
        if (rts != null) for (r in 0 until rts.length()) { val ex = rts.optJSONObject(r)?.optJSONArray("ex") ?: continue; for (k in 0 until ex.length()) { val o = ex.optJSONObject(k) ?: continue; add(o.optString("n"), o.optString("i")) } }
        return out
    }
    /* Exercices similaires : ceux de la séance, sinon ceux connus dans tes routines pour le même exercice. */
    fun altOf(e: Int): JSONArray? {
        val x = ex(e) ?: return null
        x.optJSONArray("alt")?.let { if (it.length() > 0) return it }
        val n = x.optString("n"); val rts = routines() ?: return null
        for (r in 0 until rts.length()) { val ex = rts.optJSONObject(r)?.optJSONArray("ex") ?: continue; for (k in 0 until ex.length()) { val o = ex.optJSONObject(k) ?: continue; if (o.optString("n") == n) o.optJSONArray("alt")?.let { if (it.length() > 0) return it } } }
        return null
    }
    fun imgOf(n: String): String {
        val rts = routines() ?: return ""
        for (r in 0 until rts.length()) { val ex = rts.optJSONObject(r)?.optJSONArray("ex") ?: continue; for (k in 0 until ex.length()) { val o = ex.optJSONObject(k) ?: continue; if (o.optString("n") == n && o.optString("i").isNotEmpty()) return o.optString("i") } }
        return ""
    }

    fun swap(ctx: Context, e: Int, n: String, l: String?, img: String = "") {
        if (local == null) { val m = JSONObject().put("a", "swap").put("e", e).put("n", n); if (!l.isNullOrEmpty()) m.put("l", l); send(ctx, m); return }
        val x = ex(e) ?: return
        x.put("n", n).put("i", img).put("alt", JSONArray())
        val ss = x.optJSONArray("s") ?: JSONArray()
        for (k in 0 until ss.length()) { val y = ss.optJSONObject(k) ?: continue; if (y.optInt("d", 0) != 1) y.put("w", "").put("p", "") }
        edits.keys.removeIf { it.startsWith("$e:") }
        saveLocal(); changed()
    }

    fun ex(e: Int): JSONObject? = st?.optJSONArray("ex")?.optJSONObject(e)
    fun nEx() = st?.optJSONArray("ex")?.length() ?: 0
    fun nSets(e: Int) = ex(e)?.optJSONArray("s")?.length() ?: 0
    fun set(e: Int, s: Int): JSONObject? = ex(e)?.optJSONArray("s")?.optJSONObject(s)
    fun isDone(e: Int, s: Int) = set(e, s)?.optInt("d", 0) == 1 || pending.containsKey("$e:$s")
    fun doneCount(): Pair<Int, Int> { var d = 0; var n = 0; for (e in 0 until nEx()) for (s in 0 until nSets(e)) { n++; if (isDone(e, s)) d++ }; return d to n }

    /* Série en cours : la première non cochée, en partant de l'exercice choisi. */
    fun cur(): Pair<Int, Int>? {
        val n = nEx(); if (n == 0) return null
        if (selE == exSel && exSel in 0 until n && selS in 0 until nSets(exSel) && !isDone(exSel, selS)) return exSel to selS
        val order = if (exSel in 0 until n) (exSel until n) + (0 until exSel) else (0 until n)
        for (e in order) for (s in 0 until nSets(e)) if (!isDone(e, s)) return e to s
        return null
    }

    fun update(j: String) {
        val o = try { JSONObject(j) } catch (e: Exception) { return }
        val old = phone
        phone = o
        ver++
        app?.let { try { it.getSharedPreferences("rl_ws", Context.MODE_PRIVATE).edit().putString("phone", j).apply() } catch (e: Exception) {} }
        val now = System.currentTimeMillis()
        lastState = now
        if (phone?.optInt("on", 0) != 1) endedId = null
        if (phoneOn()) startWait = null
        if (now - lastFlush > 20000) app?.let { lastFlush = now; flush(it) }
        if (local != null) { changed(); return }   // séance sur la montre seule : on garde la nôtre
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
        init(ctx)
        Wearable.getDataClient(ctx).dataItems.addOnSuccessListener { buf ->
            var j: String? = null
            for (it in buf) if (it.uri.path == "/rl/state") j = DataMapItem.fromDataItem(it).dataMap.getString("j")
            buf.release()
            if (j != null) update(j)
        }
        flush(ctx)
    }

    fun send(ctx: Context, body: JSONObject) {
        val data = body.toString().toByteArray(Charsets.UTF_8)
        val a = ctx.applicationContext
        Wearable.getNodeClient(a).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { Wearable.getMessageClient(a).sendMessage(it.id, "/rl/cmd", data) }
        }
    }

    /* Charge et répétitions affichées : celles modifiées sur la montre, sinon celles de la séance. */
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
        val a = (st?.optString("acc") ?: phone?.optString("acc") ?: "").trim()
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
    fun startMs(): Long = try { java.time.Instant.parse(st?.optString("st")).toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() }
    fun elapsedMs(): Long = System.currentTimeMillis() - startMs()
    fun kcal(): Int = (5.0 * (st?.optInt("kg", 75) ?: 75) * elapsedMs() / 3600000.0).toInt()

    /* ---------- Démarrer : avec le téléphone si son appli répond, sinon seule ---------- */
    fun start(ctx: Context, rid: String) {
        init(ctx)
        if (local != null) return
        val a = ctx.applicationContext
        Wearable.getNodeClient(a).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) { goLocal(a, rid); return@addOnSuccessListener }
                startWait = rid
                val t0 = System.currentTimeMillis()
                startTag = t0
                send(a, JSONObject().put("a", "start").put("rid", rid).put("t", t0))
                main.postDelayed({
                    if (startWait == rid && !phoneOn()) {
                        // Le téléphone n'a pas répondu à temps : la montre continue seule,
                        // et on dit au téléphone d'oublier ce démarrage s'il le reçoit plus tard.
                        send(a, JSONObject().put("a", "start-cancel").put("t", t0))
                        goLocal(a, rid)
                    }
                }, 6000)
            }
            .addOnFailureListener { goLocal(a, rid) }
    }

    /* Le téléphone répond que son appli est fermée : la montre continue seule. */
    fun noPhone(ctx: Context) { val r = startWait ?: return; goLocal(ctx.applicationContext, r) }

    private fun goLocal(ctx: Context, rid: String) {
        init(ctx)
        if (local != null || phoneOn()) return
        startWait = null
        val rts = routines() ?: return
        var r: JSONObject? = null
        for (i in 0 until rts.length()) if (rts.optJSONObject(i)?.optString("id") == rid) r = rts.optJSONObject(i)
        r ?: return
        val now = java.time.Instant.now().toString()
        val ex = JSONArray()
        val src = r.optJSONArray("ex") ?: JSONArray()
        for (i in 0 until src.length()) {
            val e = src.optJSONObject(i) ?: continue
            val sl = e.optJSONArray("sl")
            val sets = JSONArray()
            if (sl != null && sl.length() > 0) for (k in 0 until sl.length()) { val x = sl.optJSONObject(k) ?: continue; sets.put(JSONObject().put("w", x.opt("w") ?: "").put("r", x.optInt("r", 0)).put("t", x.optString("t")).put("p", x.optString("p")).put("d", 0)) }
            else for (k in 0 until maxOf(1, e.optInt("s", 3))) sets.put(JSONObject().put("w", "").put("r", 10).put("t", "").put("p", "").put("d", 0))
            ex.put(JSONObject().put("n", e.optString("n")).put("i", e.optString("i")).put("alt", e.optJSONArray("alt") ?: JSONArray()).put("rest", e.optInt("rest", 90)).put("c", e.optInt("c", 0)).put("s", sets))
        }
        local = JSONObject().put("on", 1).put("local", 1).put("id", now).put("st", now).put("rid", rid).put("n", r.optString("n"))
            .put("u", phone?.optString("u", "lb") ?: "lb").put("kg", phone?.optInt("kg", 75) ?: 75)
            .put("lang", phone?.optString("lang", "fr") ?: "fr").put("acc", phone?.optString("acc") ?: "").put("ex", ex)
        hrl = JSONArray(); exSel = -1; pending.clear(); edits.clear(); restEnd = 0
        saveLocal(); changed()
    }

    /* ---------- Actions (téléphone ou séance locale) ---------- */
    fun done(ctx: Context, e: Int, s: Int) {
        val now = System.currentTimeMillis()
        if (local != null) {
            val x = set(e, s) ?: return
            edits["$e:$s"]?.let { x.put("w", it[0]).put("r", it[1].toInt()) }
            x.put("d", 1); edits.remove("$e:$s"); saveLocal()
        } else {
            pending["$e:$s"] = now
            val m = JSONObject().put("a", "done").put("e", e).put("s", s)
            edits["$e:$s"]?.let { m.put("w", it[0]).put("r", it[1].toInt()) }
            edits.remove("$e:$s")
            send(ctx, m); ver++
        }
        val x = ex(e)
        val tp = set(e, s)?.optString("t", "") ?: ""
        val rw = phone?.optInt("rw", 45)?.takeIf { it > 0 } ?: 45
        val rd = phone?.optInt("rd", 0) ?: 0
        val r = if (tp == "W") rw else if (tp == "D" && rd > 0) rd else (x?.optInt("rest", 90) ?: 90)
        if (r > 0 && x?.optInt("c", 0) != 1) { restTot = r; restEnd = now + r * 1000L }
        if (exSel == e && (0 until nSets(e)).all { isDone(e, it) }) exSel = -1
        changed()
    }

    fun cmd(ctx: Context, a: String, e: Int = -1, s: Int = -1, t: String? = null) {
        val l = local
        if (l == null) {
            val m = JSONObject().put("a", a); if (e >= 0) m.put("e", e); if (s >= 0) m.put("s", s); if (t != null) m.put("t", t)
            send(ctx, m)
            // Abandon : la montre arrête tout de suite, le téléphone suivra dès qu'il reçoit le message.
            if (a == "cancel") { endedId = phone?.optString("id"); restEnd = 0; exSel = -1; edits.clear(); pending.clear(); changed() }
            return
        }
        val sets = ex(e)?.optJSONArray("s")
        when (a) {
            "add-set" -> if (sets != null) { val last = sets.optJSONObject(sets.length() - 1); sets.put(JSONObject().put("w", last?.opt("w") ?: "").put("r", last?.optInt("r", 10) ?: 10).put("t", if (last?.optString("t") == "W") "" else last?.optString("t") ?: "").put("p", "").put("d", 0)) }
            "type" -> set(e, s)?.put("t", t ?: "")
            "del-set" -> if (sets != null && sets.length() > 1) { sets.remove(s); edits.clear() }
            "cancel" -> { local = null; hrl = JSONArray(); restEnd = 0; exSel = -1; edits.clear() }
        }
        saveLocal(); changed()
    }

    fun finish(ctx: Context) {
        val l = local
        if (l == null) { send(ctx, JSONObject().put("a", "finish")); endedId = phone?.optString("id"); restEnd = 0; exSel = -1; edits.clear(); pending.clear(); changed(); return }
        val ex = JSONArray()
        val src = l.optJSONArray("ex") ?: JSONArray()
        for (i in 0 until src.length()) {
            val e = src.optJSONObject(i) ?: continue
            val sets = JSONArray(); val ss = e.optJSONArray("s") ?: JSONArray()
            for (k in 0 until ss.length()) { val x = ss.optJSONObject(k) ?: continue; sets.put(JSONObject().put("w", x.opt("w") ?: "").put("r", x.optInt("r", 0)).put("t", x.optString("t")).put("d", x.optInt("d", 0))) }
            ex.put(JSONObject().put("n", e.optString("n")).put("s", sets))
        }
        val payload = JSONObject().put("id", l.optString("id")).put("n", l.optString("n")).put("rid", l.optString("rid"))
            .put("st", l.optString("st")).put("end", System.currentTimeMillis()).put("ex", ex).put("hr", hrl)
        val p = prefs()
        val box = try { JSONArray(p.getString("outbox", "[]")) } catch (e: Exception) { JSONArray() }
        box.put(JSONObject().put("a", "import").put("s", payload).toString())
        p.edit().putString("outbox", box.toString()).apply()
        local = null; hrl = JSONArray(); restEnd = 0; exSel = -1; edits.clear(); pending.clear()
        saveLocal(); changed()
        flush(ctx)
    }

    /* Envoie au téléphone les séances faites sur la montre seule (réessaie tant que ce n'est pas passé). */
    fun flush(ctx: Context) {
        init(ctx)
        val p = prefs()
        val box = try { JSONArray(p.getString("outbox", "[]")) } catch (e: Exception) { JSONArray() }
        if (box.length() == 0) return
        val a = ctx.applicationContext
        Wearable.getNodeClient(a).connectedNodes.addOnSuccessListener { nodes ->
            val n = nodes.firstOrNull() ?: return@addOnSuccessListener
            for (i in 0 until box.length()) {
                val item = box.optString(i)
                Wearable.getMessageClient(a).sendMessage(n.id, "/rl/cmd", item.toByteArray(Charsets.UTF_8)).addOnSuccessListener {
                    val cur = try { JSONArray(p.getString("outbox", "[]")) } catch (e: Exception) { JSONArray() }
                    val keep = JSONArray(); for (k in 0 until cur.length()) if (cur.optString(k) != item) keep.put(cur.optString(k))
                    p.edit().putString("outbox", keep.toString()).apply()
                }
            }
        }
    }
    fun outboxCount(): Int = try { JSONArray(prefs().getString("outbox", "[]")).length() } catch (e: Exception) { 0 }

    /* Cardio : envoyé en direct au téléphone, ou gardé dans la séance locale. */
    fun hr(ctx: Context, b: Int, now: Long) {
        val l = local
        if (l == null) { send(ctx, JSONObject().put("a", "hr").put("bpm", b).put("t", now)); return }
        hrl.put(JSONArray().put((now - startMs()) / 1000).put(b))
        if (hrl.length() % 12 == 0) saveLocal()
    }

    fun restAdd(ctx: Context) {
        val now = System.currentTimeMillis()
        if (restEnd > now) { restEnd += 30000; restTot += 30; if (local == null) send(ctx, JSONObject().put("a", "rest").put("add", 30)) }
        changed()
    }

    fun phoneMode() = local == null
    fun bigTimer(ctx: Context) { send(ctx, JSONObject().put("a", "bigtimer")) }

    fun restSkip(ctx: Context) {
        restEnd = 0
        if (local == null) send(ctx, JSONObject().put("a", "rest").put("skip", 1))
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
