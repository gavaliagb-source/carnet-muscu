package app.runelift.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/* Écran de la montre, inspiré de Hevy : démarrer une routine, ajuster charge/reps (écran ou lunette), cocher, repos, terminer. */
class WatchActivity : Activity() {
    companion object { @Volatile var visible = false }

    private val BG = Color.parseColor("#0D0F12")
    private val ACC = Color.parseColor("#C6F135")
    private val MUTED = Color.parseColor("#8A93A0")
    private val HEART = Color.parseColor("#EF5A6F")
    private val CARD = Color.parseColor("#1F242C")
    private val SEL = Color.parseColor("#2C3440")

    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val uiCb: () -> Unit = { paint() }
    private val loop = object : Runnable {
        override fun run() { WS.checkRest(this@WatchActivity); paintLight(); h.postDelayed(this, 500) }
    }
    private var field = 0          // 0 = charge, 1 = répétitions (ce que la lunette modifie)
    private var finishArm = 0L     // « Terminer » demande un 2e toucher
    private var sig = ""           // évite de tout reconstruire à chaque seconde
    private var tvTop: TextView? = null
    private var tvBig: TextView? = null
    private var rotAcc = 0f

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun pill(text: String, bg: Int, fg: Int, sp: Float, padH: Float, padV: Float, click: (View) -> Unit) = tv(text, sp, fg, true).apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(40f).toFloat() }
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        isClickable = true; isFocusable = true
        setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); click(v) }
    }
    private fun add(v: View, top: Float = 6f, full: Boolean = true) {
        col.addView(v, LinearLayout.LayoutParams(if (full) -1 else -2, -2).apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL })
    }
    private fun row(vararg vs: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        vs.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3f), 0, dp(3f), 0) }) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val w = resources.displayMetrics.widthPixels
            setPadding((w * 0.11).toInt(), (w * 0.12).toInt(), (w * 0.11).toInt(), (w * 0.18).toInt())
        }
        scroll.addView(col)
        setContentView(scroll)
    }

    /* Lunette tactile / couronne : ajuste la charge ou les répétitions pendant la séance. */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && WS.on() && WS.restEnd <= System.currentTimeMillis()) {
            val c = WS.cur()
            if (c != null && WS.ex(c.first)?.optInt("c", 0) != 1) {
                rotAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).scaledVerticalScrollFactor
                val stepPx = dp(28f).toFloat()
                while (rotAcc >= stepPx) { rotAcc -= stepPx; WS.adjust(c.first, c.second, field, 1.0); scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                while (rotAcc <= -stepPx) { rotAcc += stepPx; WS.adjust(c.first, c.second, field, -1.0); scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                return true
            }
        }
        return super.onGenericMotionEvent(ev)
    }

    private fun move(d: Int) {
        val n = WS.nEx(); if (n == 0) return
        var e = WS.cur()?.first ?: 0
        for (i in 1..n) { e = ((e + d) % n + n) % n; if ((0 until WS.nSets(e)).any { !WS.isDone(e, it) }) break }
        WS.exSel = e; field = 0; paint()
    }

    private fun perms() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission("android.permission.health.READ_HEART_RATE") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.health.READ_HEART_RATE")
        val p = getSharedPreferences("rl", MODE_PRIVATE)
        if (need.isNotEmpty() && !p.getBoolean("asked", false)) { p.edit().putBoolean("asked", true).apply(); requestPermissions(need.toTypedArray(), 3) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        service()
    }

    private fun service() {
        val i = Intent(this, HrService::class.java)
        if (WS.on() && checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(i) } catch (e: Exception) {}
        } else if (!WS.on()) {
            try { stopService(i) } catch (e: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        WS.onUi(uiCb)
        WS.load(this)
        WS.send(this, JSONObject().put("a", "hello"))
        perms()
        sig = ""; paint()
        scroll.requestFocus()
        h.post(loop)
    }

    override fun onPause() {
        visible = false
        WS.offUi(uiCb)
        h.removeCallbacks(loop)
        super.onPause()
    }

    private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60) }

    private fun topText(): String {
        val now = System.currentTimeMillis()
        val parts = mutableListOf<String>()
        WS.st?.optString("st")?.let { iso -> try { parts.add(clock(now - java.time.Instant.parse(iso).toEpochMilli())) } catch (e: Exception) {} }
        if (WS.bpm > 0 && now - WS.bpmT < 20000) parts.add("♥ ${WS.bpm}")
        val (d, n) = WS.doneCount(); parts.add("$d/$n")
        return parts.joinToString("  ·  ")
    }

    /* Mise à jour légère chaque demi-seconde (chrono, cardio, compte à rebours). */
    private fun paintLight() {
        if (WS.on() != lastOn) { paint(); return }
        if (!WS.on()) return
        tvTop?.text = topText()
        val now = System.currentTimeMillis()
        if (WS.restEnd > now) tvBig?.text = clock(WS.restEnd - now + 999)
        else if (sig.startsWith("rest")) paint()
    }

    private var lastOn = false
    private fun paint() {
        val on = WS.on()
        if (on != lastOn) { lastOn = on; service() }
        val now = System.currentTimeMillis()
        val c = WS.cur()
        val resting = on && WS.restEnd > now
        val ns = if (!on) "idle:" + (WS.st?.optJSONArray("rts")?.toString() ?: "") + WS.lastState.coerceAtMost(1)
        else (if (resting) "rest" else "set") + ":" + c + ":" + field + ":" + (c?.let { WS.w(it.first, it.second).toString() + "x" + WS.r(it.first, it.second) } ?: "") + ":" + WS.pending.size + ":" + finishArm + ":" + WS.nEx()
        if (ns == sig) { paintLight(); return }
        sig = ns
        col.removeAllViews(); tvTop = null; tvBig = null
        if (!on) { idle(); return }
        tvTop = tv(topText(), 12f, MUTED, true).also { add(it, 0f) }
        if (resting) { rest(c); return }
        if (c == null) { allDone(); return }
        setView(c.first, c.second)
    }

    private fun idle() {
        add(tv("Runelift", 18f, ACC, true), 0f)
        val rts = WS.st?.optJSONArray("rts")
        if (rts == null || rts.length() == 0) {
            add(tv(WS.t("Ouvre Runelift sur ton téléphone pour voir tes routines.", "Open Runelift on your phone to see your routines."), 13f, MUTED), 8f)
            return
        }
        add(tv(WS.t("Démarrer une routine", "Start a routine"), 12f, MUTED), 4f)
        for (i in 0 until rts.length()) {
            val r = rts.optJSONObject(i) ?: continue
            val b = pill(r.optString("n") + "\n" + WS.t("${r.optInt("c")} exercices", "${r.optInt("c")} exercises"), CARD, Color.WHITE, 14f, 14f, 10f) {
                WS.start(this, r.optString("id"))
                (it as TextView).text = WS.t("Démarrage…", "Starting…")
            }
            add(b, 8f)
        }
    }

    private fun rest(c: Pair<Int, Int>?) {
        add(tv(WS.t("Repos", "Rest"), 13f, MUTED), 6f)
        tvBig = tv(clock(WS.restEnd - System.currentTimeMillis() + 999), 34f, Color.WHITE, true).also { add(it, 2f) }
        val next = if (c != null) WS.t("Ensuite : ", "Next: ") + (WS.ex(c.first)?.optString("n") ?: "") else WS.t("Dernière série faite", "Last set done")
        add(tv(next, 13f, Color.WHITE).apply { maxLines = 2 }, 4f)
        add(row(pill("+30 s", CARD, Color.WHITE, 14f, 14f, 8f) { WS.restAdd(this) },
                pill(WS.t("Passer", "Skip"), ACC, Color.BLACK, 14f, 16f, 8f) { WS.restSkip(this) }), 10f)
    }

    private fun allDone() {
        add(tv(WS.t("Tout est fait !", "All done!"), 17f, ACC, true), 8f)
        finishButton(12f)
    }

    private fun finishButton(top: Float) {
        val armed = System.currentTimeMillis() - finishArm < 4000
        add(pill(if (armed) WS.t("Confirmer : terminer", "Confirm: finish") else WS.t("Terminer la séance", "Finish workout"),
            if (armed) HEART else CARD, Color.WHITE, 13f, 14f, 8f) {
            if (System.currentTimeMillis() - finishArm < 4000) { finishArm = 0; WS.finish(this) }
            else { finishArm = System.currentTimeMillis(); h.postDelayed({ paint() }, 4100) }
            paint()
        }, top, false)
    }

    private fun setView(e: Int, s: Int) {
        val x = WS.ex(e); val st = WS.set(e, s)
        val cardio = x?.optInt("c", 0) == 1
        add(tv(x?.optString("n") ?: "", 16f, Color.WHITE, true).apply { maxLines = 2 }, 4f)
        val warm = st?.optString("t") == "W"
        add(tv((if (warm) WS.t("Échauffement · ", "Warm-up · ") else "") + WS.t("Série ${s + 1}/${WS.nSets(e)}", "Set ${s + 1}/${WS.nSets(e)}"), 12f, MUTED), 2f)
        if (cardio) {
            add(tv(WS.t("Exercice chronométré ou cardio : à faire sur le téléphone", "Timed or cardio exercise: use the phone"), 12f, MUTED), 8f)
            add(pill(WS.t("Exercice suivant ›", "Next exercise ›"), ACC, Color.BLACK, 15f, 20f, 9f) { move(1) }, 10f, false)
            add(row(pill("‹", CARD, Color.WHITE, 18f, 14f, 4f) { move(-1) }, pill("›", CARD, Color.WHITE, 18f, 14f, 4f) { move(1) }), 8f)
            finishButton(10f)
            return
        }
        val u = WS.st?.optString("u", "lb") ?: "lb"
        val wv = WS.w(e, s); val rv = WS.r(e, s)
        val wTxt = if (wv > 0 || WS.hasW(e, s)) "${WS.num(wv)} $u" else WS.t("Poids du corps", "Bodyweight")
        fun valRow(label: String, value: String, f: Int) = row(
            pill("−", CARD, Color.WHITE, 18f, 13f, 2f) { WS.adjust(e, s, f, -1.0) },
            tv(value, if (value.length > 9) 17f else 22f, if (field == f) ACC else Color.WHITE, true).apply {
                minWidth = dp(92f)
                background = GradientDrawable().apply { setColor(if (field == f) SEL else BG); cornerRadius = dp(12f).toFloat() }
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                contentDescription = label
                setOnClickListener { field = f; paint() }
            },
            pill("+", CARD, Color.WHITE, 18f, 13f, 2f) { WS.adjust(e, s, f, 1.0) })
        add(valRow(WS.t("Charge", "Weight"), wTxt, 0), 8f)
        add(valRow(WS.t("Répétitions", "Reps"), "× ${WS.num(rv)}", 1), 6f)
        add(tv(WS.t("Lunette : ajuste la valeur en vert", "Bezel: adjusts the green value"), 10f, MUTED), 4f)
        add(pill(WS.t("✓  Fait", "✓  Done"), ACC, Color.BLACK, 17f, 30f, 10f) { field = 0; WS.done(this, e, s) }, 10f, false)
        add(row(pill("‹", CARD, Color.WHITE, 18f, 14f, 4f) { move(-1) }, pill("›", CARD, Color.WHITE, 18f, 14f, 4f) { move(1) }), 8f)
        finishButton(10f)
    }
}
