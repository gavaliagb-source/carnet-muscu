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
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/* Écran de la montre : exercice et série en cours, bouton « Fait », repos, cardio. */
class WatchActivity : Activity() {
    companion object { @Volatile var visible = false }

    private val BG = Color.parseColor("#0D0F12")
    private val ACC = Color.parseColor("#C6F135")
    private val MUTED = Color.parseColor("#8A93A0")
    private val HEART = Color.parseColor("#EF5A6F")
    private val CARD = Color.parseColor("#1F242C")

    private lateinit var tvTop: TextView
    private lateinit var tvEx: TextView
    private lateinit var tvSet: TextView
    private lateinit var tvBig: TextView
    private lateinit var btnMain: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnPlus: TextView
    private lateinit var navRow: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val uiCb: () -> Unit = { paint() }
    private val loop = object : Runnable {
        override fun run() { WS.checkRest(this@WatchActivity); paint(); h.postDelayed(this, 500) }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun pill(text: String, bg: Int, fg: Int, sp: Float, padH: Float, padV: Float) = tv(sp, fg, true).apply {
        this.text = text
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(40f).toFloat() }
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        isClickable = true; isFocusable = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val w = resources.displayMetrics.widthPixels
            setPadding((w * 0.12).toInt(), (w * 0.10).toInt(), (w * 0.12).toInt(), (w * 0.08).toInt())
        }
        tvTop = tv(13f, MUTED, true)
        tvEx = tv(16f, Color.WHITE, true).apply { maxLines = 2 }
        tvSet = tv(12f, MUTED)
        tvBig = tv(28f, ACC, true)
        btnMain = pill("", ACC, Color.BLACK, 16f, 26f, 9f)
        btnPrev = pill("‹", CARD, Color.WHITE, 18f, 14f, 4f)
        btnNext = pill("›", CARD, Color.WHITE, 18f, 14f, 4f)
        btnPlus = pill("+30 s", CARD, Color.WHITE, 13f, 12f, 6f)
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4f), 0, dp(4f), 0) } }
        navRow.addView(btnPrev, lp()); navRow.addView(btnPlus, lp()); navRow.addView(btnNext, lp())
        col.addView(tvTop); col.addView(tvEx, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        col.addView(tvSet, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        col.addView(tvBig, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        col.addView(btnMain, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f); gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(navRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f); gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        btnMain.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (WS.restEnd > System.currentTimeMillis()) { WS.restSkip(this); return@setOnClickListener }
            val c = WS.cur() ?: return@setOnClickListener
            WS.done(this, c.first, c.second)
        }
        btnPlus.setOnClickListener { WS.restAdd(this) }
        btnPrev.setOnClickListener { move(-1) }
        btnNext.setOnClickListener { move(1) }
    }

    private fun move(d: Int) {
        val n = WS.nEx(); if (n == 0) return
        val c = WS.cur()?.first ?: 0
        var e = c
        for (i in 1..n) { e = ((e + d) % n + n) % n; if ((0 until WS.nSets(e)).any { !WS.isDone(e, it) }) break }
        WS.exSel = e; paint()
    }

    private fun perms(): Boolean {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission("android.permission.health.READ_HEART_RATE") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.health.READ_HEART_RATE")
        val p = getSharedPreferences("rl", MODE_PRIVATE)
        if (need.isNotEmpty() && !p.getBoolean("asked", false)) { p.edit().putBoolean("asked", true).apply(); requestPermissions(need.toTypedArray(), 3) }
        return checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
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
        h.post(loop)
    }

    override fun onPause() {
        visible = false
        WS.offUi(uiCb)
        h.removeCallbacks(loop)
        super.onPause()
    }

    private var lastOn = false
    private fun paint() {
        val on = WS.on()
        if (on != lastOn) { lastOn = on; service() }
        val now = System.currentTimeMillis()
        val hr = if (WS.bpm > 0 && now - WS.bpmT < 20000) "♥ ${WS.bpm}" else ""
        if (!on) {
            tvTop.text = ""; tvEx.text = WS.t("Aucune séance", "No workout")
            tvSet.text = WS.t("Lance une séance sur ton téléphone", "Start a workout on your phone")
            tvBig.text = ""; btnMain.visibility = View.GONE; navRow.visibility = View.GONE
            return
        }
        val (d, n) = WS.doneCount()
        tvTop.text = if (hr.isNotEmpty()) "$hr   ·   $d/$n" else "$d/$n"
        tvTop.setTextColor(if (hr.isNotEmpty()) HEART else MUTED)
        val u = WS.st?.optString("u", "lb") ?: "lb"
        val c = WS.cur()
        val resting = WS.restEnd > now
        btnMain.visibility = View.VISIBLE; navRow.visibility = View.VISIBLE
        btnPlus.visibility = if (resting) View.VISIBLE else View.GONE
        btnPrev.visibility = if (resting) View.GONE else View.VISIBLE
        btnNext.visibility = if (resting) View.GONE else View.VISIBLE
        if (resting) {
            val left = ((WS.restEnd - now + 999) / 1000).toInt()
            tvSet.text = WS.t("Repos", "Rest")
            tvBig.text = String.format("%d:%02d", left / 60, left % 60)
            tvBig.setTextColor(Color.WHITE)
            tvEx.text = if (c != null) WS.t("Ensuite : ", "Next: ") + (WS.ex(c.first)?.optString("n") ?: "") else WS.t("Dernière série faite", "Last set done")
            btnMain.text = WS.t("Passer", "Skip")
            return
        }
        tvBig.setTextColor(ACC)
        if (c == null) {
            tvEx.text = WS.t("Tout est fait !", "All done!")
            tvSet.text = WS.t("Termine la séance sur le téléphone", "Finish the workout on your phone")
            tvBig.text = ""; btnMain.visibility = View.GONE; navRow.visibility = View.GONE
            return
        }
        val (e, s) = c
        val x = WS.ex(e); val st = WS.set(e, s)
        tvEx.text = x?.optString("n") ?: ""
        val warm = st?.optString("t") == "W"
        val nWork = WS.nSets(e)
        tvSet.text = (if (warm) WS.t("Échauffement · ", "Warm-up · ") else "") + WS.t("Série ${s + 1}/$nWork", "Set ${s + 1}/$nWork")
        val w = WS.num(st?.opt("w")); val r = WS.num(st?.opt("r"))
        tvBig.text = when {
            x?.optInt("c", 0) == 1 -> WS.t("Sur le téléphone", "On the phone")
            w.isNotEmpty() && w != "0" -> "$w $u × $r"
            else -> "× $r"
        }
        tvBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvBig.text.length > 12) 22f else 28f)
        btnMain.text = WS.t("✓  Fait", "✓  Done")
    }
}
