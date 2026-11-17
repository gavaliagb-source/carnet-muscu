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
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/* Écran de la montre, façon Hevy : 3 pages pendant la séance (glisser à gauche / droite).
   Page 0 = tous les exercices, page 1 = série en cours, page 2 = options. */
class WatchActivity : Activity() {
    companion object { @Volatile var visible = false }

    private val BG = Color.parseColor("#0D0F12")
    private val MUTED = Color.parseColor("#8A93A0")
    private val HEART = Color.parseColor("#EF5A6F")
    private val CARD = Color.parseColor("#262C35")
    private val RED = Color.parseColor("#FF5A5A")
    private val ACC get() = WS.acc()
    private val ON_ACC get() = WS.onAcc()

    private lateinit var scroll: LockScroll
    private lateinit var col: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val uiCb: () -> Unit = { paint() }
    private val loop = object : Runnable {
        override fun run() { WS.checkRest(this@WatchActivity); paintLight(); h.postDelayed(this, 500) }
    }
    private var page = 1
    private var detail: String? = null      // routine ouverte (avant de commencer)
    private var field = 0                   // 0 = charge, 1 = répétitions
    private var arm = ""                    // bouton qui attend une confirmation
    private var armT = 0L
    private var sig = ""
    private var tvClock: TextView? = null
    private var tvHr: TextView? = null
    private var tvBig: TextView? = null
    private var rotAcc = 0f
    private var lastRot = 0L
    private var streak = 0
    private var dragging = false
    private var askAb = false      // page « Es-tu sûr de vouloir abandonner ? »
    private var swapFor = -1       // liste pour remplacer un exercice
    private lateinit var gd: GestureDetector

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun bg(color: Int, r: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(2f), stroke) }
    private fun pill(text: String, bgc: Int, fg: Int, sp: Float = 14f, padV: Float = 10f, click: (View) -> Unit) = tv(text, sp, fg, true).apply {
        background = bg(bgc, 40f)
        setPadding(dp(14f), dp(padV), dp(14f), dp(padV))
        isClickable = true; isFocusable = true
        setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); click(v) }
    }
    private fun circle(text: String, click: (View) -> Unit) = tv(text, 16f, Color.WHITE, true).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
        layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
        isClickable = true; isFocusable = true
        setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); click(v) }
    }
    /* Nom trop long : il défile tout seul, comme dans Hevy. */
    private fun marquee(text: String, sp: Float, color: Int) = tv(text, sp, color, true).apply {
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1
        setHorizontallyScrolling(true); isSelected = true
    }
    private fun add(v: View, top: Float = 6f, full: Boolean = true) {
        col.addView(v, LinearLayout.LayoutParams(if (full) -1 else -2, -2).apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL })
    }
    private fun row(vararg vs: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        vs.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3f), 0, dp(3f), 0) }) }
    }
    private fun img(url: String?, size: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = bg(Color.WHITE, size)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        if (!url.isNullOrEmpty()) Img.load(this@WatchActivity, url) { b -> if (isAttachedToWindow || parent != null) setImageBitmap(b) }
    }
    private fun withImg(url: String?, content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(img(url, 34f))
        addView(content, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8f) })
    }
    private fun armed(k: String) = arm == k && System.currentTimeMillis() - armT < 4000
    private fun confirmOr(k: String, run: () -> Unit) {
        if (armed(k)) { arm = ""; run() } else { arm = k; armT = System.currentTimeMillis(); h.postDelayed({ paint() }, 4100) }
        paint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WS.init(this)
        fromTile(intent)
        scroll = LockScroll(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val w = resources.displayMetrics.widthPixels
            setPadding((w * 0.12).toInt(), (w * 0.07).toInt(), (w * 0.12).toInt(), (w * 0.12).toInt())
            minimumHeight = resources.displayMetrics.heightPixels
        }
        scroll.addView(col)
        setContentView(scroll)
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || !WS.on()) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dx) < dp(45f) || abs(dx) < abs(dy) * 1.4f) return false
                val np = if (dx < 0) (page + 1).coerceAtMost(2) else (page - 1).coerceAtLeast(0)
                if (np != page) { page = np; scroll.scrollTo(0, 0); scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); paint() }
                return true
            }
        })
    }

    private fun fromTile(i: Intent?) {
        val rid = i?.getStringExtra("start") ?: return
        i.removeExtra("start")
        if (!WS.on() && rid.isNotEmpty()) { WS.load(this); h.postDelayed({ WS.start(this, rid) }, 400) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); fromTile(intent) }

    /* Lunette tactile / couronne : on la prend avant la liste qui défile, pour monter ou descendre les chiffres. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && onGenericMotionEvent(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gd.onTouchEvent(ev)) {
            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (askAb || swapFor >= 0) { askAb = false; swapFor = -1; paint(); return }
        if (!WS.on() && detail != null) { detail = null; paint(); return }
        if (WS.on() && page != 1) { page = 1; paint(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    /* Lunette / couronne : ajuste la charge ou les répétitions de la série en cours. */
    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) && WS.on() && page == 1 && WS.restEnd <= System.currentTimeMillis()) {
            val c = WS.cur()
            if (c != null && WS.ex(c.first)?.optInt("c", 0) != 1) {
                val v = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (v == 0f) return true
                // Lunette = réglage précis : un cran à la fois.
                WS.adjust(c.first, c.second, field, if (v > 0) 1.0 else -1.0)
                scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
    private fun hrLine(): String {
        val now = System.currentTimeMillis()
        val hr = if (WS.bpm > 0 && now - WS.bpmT < 20000) "♥ ${WS.bpm}   " else ""
        return "$hr${WS.kcal()} kcal"
    }

    private fun paintLight() {
        if (WS.on() != lastOn) { paint(); return }
        if (!WS.on()) return
        tvClock?.text = clock(WS.elapsedMs())
        tvHr?.text = hrLine()
        val now = System.currentTimeMillis()
        if (WS.restEnd > now) tvBig?.text = clock(WS.restEnd - now + 999)
        else if (sig.contains("|rest|")) paint()
    }

    private var lastOn = false
    private fun paint() {
        if (dragging) return
        val on = WS.on()
        if (on != lastOn) { lastOn = on; service(); page = 1; detail = null; askAb = false; swapFor = -1 }
        val now = System.currentTimeMillis()
        val c = WS.cur()
        val resting = on && WS.restEnd > now
        val ns = listOf(on, page, detail, if (resting) "|rest|" else "|set|", c, field, WS.st?.toString()?.hashCode(), WS.pending.keys.sorted(), WS.edits.entries.joinToString { it.key + it.value.joinToString() }, if (armed(arm)) arm else "", WS.exSel, Img.version, WS.isLocal(), askAb, swapFor).joinToString("#")
        if (ns == sig) { paintLight(); return }
        sig = ns
        col.removeAllViews(); tvClock = null; tvHr = null; tvBig = null
        col.gravity = if (on && page == 1) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        scroll.locked = on && page == 1 && !askAb && swapFor < 0
        if (!on) { if (detail != null) routineDetail(detail!!) else idle(); return }
        if (askAb) { col.gravity = Gravity.CENTER; abandonView(); return }
        if (swapFor >= 0) { swapView(swapFor); return }
        dots()
        when (page) {
            0 -> overview()
            2 -> options(c)
            else -> if (resting) rest(c) else if (c == null) allDone() else setView(c.first, c.second)
        }
    }

    private fun dots() {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0..2) r.addView(View(this).apply { background = bg(if (i == page) Color.WHITE else Color.parseColor("#4A515C"), 10f) }, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        add(r, 0f)
    }

    /* ---------- Hors séance : routines ---------- */
    private fun idle() {
        add(tv("Runelift", 17f, ACC, true), 0f)
        val rts = WS.routines()
        if (rts == null || rts.length() == 0) {
            add(tv(WS.t("Ouvre Runelift sur ton téléphone pour voir tes routines.", "Open Runelift on your phone to see your routines."), 13f, MUTED), 8f)
            return
        }
        val ob = WS.outboxCount()
        if (ob > 0) add(tv(WS.t("$ob séance(s) en attente d'envoi au téléphone", "$ob workout(s) waiting to be sent to the phone"), 11f, ACC), 4f)
        add(tv(WS.t("Mes routines", "My routines"), 13f, Color.WHITE, true), 6f)
        for (i in 0 until rts.length()) {
            val r = rts.optJSONObject(i) ?: continue
            val today = r.optInt("td", 0) == 1
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = bg(CARD, 24f, if (today) ACC else 0)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                isClickable = true
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); detail = r.optString("id"); scroll.scrollTo(0, 0); paint() }
            }
            if (today) box.addView(tv(WS.t("Aujourd'hui", "Today"), 11f, ACC, true))
            box.addView(tv(r.optString("n"), 14f, Color.WHITE, true))
            box.addView(tv(WS.t("${r.optJSONArray("ex")?.length() ?: 0} exercices", "${r.optJSONArray("ex")?.length() ?: 0} exercises"), 11f, MUTED))
            add(box, 8f)
        }
    }

    private fun routineDetail(id: String) {
        val rts = WS.routines() ?: run { detail = null; idle(); return }
        var r: JSONObject? = null
        for (i in 0 until rts.length()) if (rts.optJSONObject(i)?.optString("id") == id) r = rts.optJSONObject(i)
        if (r == null) { detail = null; idle(); return }
        add(tv(r.optString("n"), 15f, Color.WHITE, true), 0f)
        add(pill(WS.t("Commencer", "Start"), ACC, ON_ACC, 15f) { v -> WS.start(this, id); (v as TextView).text = WS.t("Démarrage…", "Starting…") }, 8f)
        val ex = r.optJSONArray("ex")
        add(tv(WS.t("${ex?.length() ?: 0} exercices", "${ex?.length() ?: 0} exercises"), 13f, Color.WHITE, true), 10f)
        for (i in 0 until (ex?.length() ?: 0)) {
            val e = ex!!.optJSONObject(i) ?: continue
            val n = e.optInt("s", 0)
            add(withImg(e.optString("i"), tv(e.optString("n") + if (n > 0) "\n" + WS.t("$n séries", "$n sets") else "", 12f, Color.WHITE).apply { gravity = Gravity.START }), 7f)
        }
        add(pill(WS.t("‹ Retour", "‹ Back"), CARD, Color.WHITE, 13f, 7f) { detail = null; paint() }, 12f, false)
    }

    /* ---------- Page 0 : vue d'ensemble ---------- */
    private fun overview() {
        add(marquee(WS.st?.optString("n") ?: "", 14f, Color.WHITE), 4f)
        tvClock = tv(clock(WS.elapsedMs()), 24f, Color.WHITE, true).also { add(it, 2f) }
        tvHr = tv(hrLine(), 13f, HEART, true).also { add(it, 0f) }
        if (WS.isLocal()) add(tv(WS.t("Montre seule · envoyée au téléphone à la fin", "Watch only · sent to the phone at the end"), 10f, MUTED), 2f)
        val cur = WS.cur()?.first
        for (e in 0 until WS.nEx()) {
            val x = WS.ex(e) ?: continue
            val n = WS.nSets(e); val d = (0 until n).count { WS.isDone(e, it) }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.START
                background = bg(CARD, 22f, if (e == cur) ACC else 0)
                setPadding(dp(8f), dp(7f), dp(12f), dp(7f))
                isClickable = true
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); WS.exSel = e; field = 0; page = 1; scroll.scrollTo(0, 0); paint() }
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                addView(tv(x.optString("n"), 13f, Color.WHITE, true).apply { gravity = Gravity.START; maxLines = 2 })
                addView(tv(WS.t("$d/$n séries", "$d/$n sets"), 11f, if (d == n && n > 0) ACC else MUTED).apply { gravity = Gravity.START }) }
            box.addView(withImg(x.optString("i"), txt))
            add(box, 7f)
        }
        add(pill(if (armed("fin")) WS.t("Confirmer : terminer", "Confirm: finish") else WS.t("Terminer l'entraînement", "Finish workout"), ACC, ON_ACC, 13f, 9f) { confirmOr("fin") { WS.finish(this) } }, 12f)
        add(pill(WS.t("Abandonner", "Discard"), CARD, RED, 13f, 9f) { askAb = true; paint() }, 6f)
    }

    /* ---------- Page 1 : série en cours ---------- */
    private fun rest(c: Pair<Int, Int>?) {
        add(tv(WS.t("Repos", "Rest"), 13f, MUTED), 6f)
        tvBig = tv(clock(WS.restEnd - System.currentTimeMillis() + 999), 34f, Color.WHITE, true).also { add(it, 2f) }
        tvHr = tv(hrLine(), 12f, HEART, true).also { add(it, 0f) }
        val next = if (c != null) WS.t("Ensuite : ", "Next: ") + (WS.ex(c.first)?.optString("n") ?: "") else WS.t("Dernière série faite", "Last set done")
        add(marquee(next, 12f, Color.WHITE), 4f)
        add(row(pill("+30 s", CARD, Color.WHITE, 13f, 8f) { WS.restAdd(this) },
                pill(WS.t("Passer", "Skip"), ACC, ON_ACC, 13f, 8f) { WS.restSkip(this) }), 10f)
    }

    private fun allDone() {
        add(tv(WS.t("Tout est fait !", "All done!"), 17f, ACC, true), 10f)
        tvHr = tv(hrLine(), 12f, HEART, true).also { add(it, 2f) }
        add(pill(if (armed("fin")) WS.t("Confirmer", "Confirm") else WS.t("Terminer l'entraînement", "Finish workout"), ACC, ON_ACC, 13f) { confirmOr("fin") { WS.finish(this) } }, 12f)
    }

    /* Tailles calculées sur la largeur de l'écran : tout tient dans le rond, quelle que soit la taille du texte de la montre. */
    private val W get() = resources.displayMetrics.widthPixels.toFloat()
    private fun tp(text: String, frac: Float, color: Int, bold: Boolean = false) = tv(text, 10f, color, bold).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, W * frac); includeFontPadding = false }
    private fun lp(w: Int, h: Int, top: Float = 0f) = LinearLayout.LayoutParams(w, h).apply { topMargin = (W * top).toInt(); gravity = Gravity.CENTER_HORIZONTAL }

    private fun setView(e: Int, s: Int) {
        val x = WS.ex(e); val st = WS.set(e, s)
        col.addView(marquee(x?.optString("n") ?: "", 13f, Color.WHITE).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.05f) }, lp((W * 0.62f).toInt(), -2, 0.01f))
        val warm = st?.optString("t") == "W"
        val (dd, nn) = s + 1 to WS.nSets(e)
        tvHr = tp((if (warm) WS.t("Échauf. · ", "Warm-up · ") else "") + "$dd/$nn   " + hrLine(), 0.04f, Color.WHITE, true).also { col.addView(it, lp(-2, -2, 0.008f)) }
        if (x?.optInt("c", 0) == 1) {
            col.addView(tp(WS.t("Exercice chronométré ou cardio :\nà faire sur le téléphone", "Timed or cardio exercise:\nuse the phone"), 0.038f, MUTED), lp((W * 0.66f).toInt(), -2, 0.03f))
            col.addView(pill(WS.t("Suivant ›", "Next ›"), ACC, ON_ACC, 14f, 8f) { move(1) }, lp(-2, -2, 0.04f))
            return
        }
        val u = WS.st?.optString("u", "lb") ?: "lb"
        fun fmt(f: Int, v: Double) = if (f == 0) (if (v <= 0 && !WS.hasW(e, s)) WS.t("PDC", "BW") else String.format(java.util.Locale.FRANCE, "%.1f", v)) else WS.num(v)
        fun box(label: String, f: Int): LinearLayout {
            val sel = field == f
            val above = tp("", 0.036f, if (sel) ACC else MUTED).apply { alpha = 0.55f }
            val mid = tp("", 0.085f, if (sel) ACC else Color.WHITE, true)
            val below = tp("", 0.036f, if (sel) ACC else MUTED).apply { alpha = 0.55f }
            fun upd() {
                val v = if (f == 0) WS.w(e, s) else WS.r(e, s); val stp = if (f == 0) WS.step() else 1.0
                above.text = if (v - stp >= (if (f == 0) 0.0 else 1.0)) fmt(f, v - stp) else " "
                mid.text = fmt(f, v); below.text = fmt(f, v + stp)
            }
            upd()
            val wheel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = bg(BG, 14f, if (sel) ACC else Color.parseColor("#4A515C"))
                setPadding(0, (W * 0.012f).toInt(), 0, (W * 0.012f).toInt())
                addView(above); addView(mid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (W * 0.006f).toInt(); bottomMargin = (W * 0.006f).toInt() }); addView(below)
            }
            var y0 = 0f; var acc = 0f; var moved = false
            var vt: android.view.VelocityTracker? = null
            wheel.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragging = true; v.parent.requestDisallowInterceptTouchEvent(true); y0 = ev.y; acc = 0f; moved = false; vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev) }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(ev)
                        acc += y0 - ev.y; y0 = ev.y
                        val stepPx = dp(10f).toFloat()
                        while (acc >= stepPx) { acc -= stepPx; moved = true; WS.adjust(e, s, f, 1.0, true); upd(); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        while (acc <= -stepPx) { acc += stepPx; moved = true; WS.adjust(e, s, f, -1.0, true); upd(); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f; vt?.recycle(); vt = null
                        if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) { dragging = false; field = f; paint() }
                        else {
                            // Coup de doigt fort = défile vite ; la lunette sert à être précis.
                            var n = (abs(vy) / dp(90f)).toInt().coerceAtMost(60)
                            val dir = if (vy < 0) 1.0 else -1.0
                            if (n > 0) {
                                var delay = 10L
                                val r = object : Runnable { override fun run() {
                                    if (n-- <= 0) { dragging = false; field = f; paint(); return }
                                    WS.adjust(e, s, f, dir, true); upd()
                                    if (n % 2 == 0) wheel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    delay += 3; h.postDelayed(this, delay)
                                } }
                                h.post(r)
                            } else { dragging = false; field = f; paint() }
                        }
                    }
                }
                true
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(tp(label, 0.034f, if (sel) ACC else MUTED, true))
                addView(wheel, LinearLayout.LayoutParams((W * 0.27f).toInt(), -2).apply { topMargin = (W * 0.008f).toInt() })
            }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(box(u.replaceFirstChar { it.uppercase() }, 0), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (W * 0.015f).toInt() })
            addView(box(WS.t("Répétitions", "Reps"), 1), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (W * 0.015f).toInt() })
        }, lp(-2, -2, 0.02f))
        val p = st?.optString("p") ?: ""
        col.addView(tp(if (p.isNotEmpty()) WS.t("précéd. ", "prev. ") + p else " ", 0.034f, MUTED), lp(-2, -2, 0.012f))
        val bd = (W * 0.13f).toInt()
        val done = tp("✓", 0.07f, ON_ACC, true).apply {
            background = bg(ACC, 40f); gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); field = 0; WS.done(this@WatchActivity, e, s) }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(circle("‹") { move(-1) }, LinearLayout.LayoutParams(bd, bd).apply { rightMargin = (W * 0.02f).toInt() })
            addView(done, LinearLayout.LayoutParams((W * 0.26f).toInt(), (W * 0.14f).toInt()))
            addView(circle("›") { move(1) }, LinearLayout.LayoutParams(bd, bd).apply { leftMargin = (W * 0.02f).toInt() })
        }, lp(-2, -2, 0.02f))
    }

    /* ---------- Abandonner : demande de confirmation ---------- */
    private fun abandonView() {
        add(tv(WS.t("Abandonner la séance ?", "Discard the workout?"), 16f, Color.WHITE, true), 0f)
        add(tv(WS.t("Elle ne sera pas enregistrée.", "It won't be saved."), 12f, MUTED), 4f)
        add(pill(WS.t("Oui, abandonner", "Yes, discard"), RED, Color.WHITE, 14f, 10f) { askAb = false; page = 1; WS.cmd(this, "cancel") }, 12f)
        add(pill(WS.t("Non, continuer", "No, keep going"), CARD, Color.WHITE, 14f, 10f) { askAb = false; paint() }, 8f)
    }

    /* ---------- Remplacer un exercice : similaires d'abord, puis les autres ---------- */
    private fun swapView(e: Int) {
        val x = WS.ex(e) ?: run { swapFor = -1; paint(); return }
        val cur = x.optString("n")
        add(tv(WS.t("Remplacer", "Replace"), 12f, MUTED), 0f)
        add(marquee(cur, 14f, Color.WHITE), 2f)
        val seen = HashSet<String>(); seen.add(cur.lowercase())
        fun item(n: String, l: String?) = add(pill(n, CARD, Color.WHITE, 12f, 8f) { WS.swap(this, e, n, l); swapFor = -1; page = 1; WS.exSel = e; paint() }, 6f)
        val alt = x.optJSONArray("alt")
        if (alt != null && alt.length() > 0) {
            add(tv(WS.t("Similaires", "Similar"), 12f, ACC, true), 10f)
            for (i in 0 until alt.length()) { val a = alt.optJSONObject(i) ?: continue; val n = a.optString("n"); if (n.isEmpty() || !seen.add(n.lowercase())) continue; item(n, a.optString("l")) }
        }
        val mine = WS.mine()
        if (mine != null && mine.length() > 0) {
            add(tv(WS.t("Mes exercices", "My exercises"), 12f, ACC, true), 10f)
            for (i in 0 until mine.length()) { val n = mine.optString(i); if (n.isEmpty() || !seen.add(n.lowercase())) continue; item(n, null) }
        }
        add(pill(WS.t("Annuler", "Cancel"), CARD, MUTED, 13f, 8f) { swapFor = -1; paint() }, 12f, false)
    }

    /* ---------- Page 2 : options ---------- */
    private fun options(c: Pair<Int, Int>?) {
        if (c != null) {
            val (e, s) = c
            add(tv(WS.t("Options de série", "Set options"), 13f, Color.WHITE, true), 4f)
            add(pill(WS.t("Ajouter une série", "Add a set"), CARD, Color.WHITE, 13f, 9f) { WS.cmd(this, "add-set", e) }, 6f)
            val t = WS.set(e, s)?.optString("t") ?: ""
            val types = listOf("" to WS.t("Normale", "Normal"), "W" to WS.t("Échauffement", "Warm-up"), "D" to "Dropset", "F" to WS.t("Échec", "Failure"))
            val i = types.indexOfFirst { it.first == t }.coerceAtLeast(0)
            add(pill(WS.t("Type : ", "Type: ") + types[i].second, CARD, Color.WHITE, 13f, 9f) { WS.cmd(this, "type", e, s, types[(i + 1) % types.size].first) }, 6f)
            if (WS.nSets(e) > 1) add(pill(if (armed("del")) WS.t("Confirmer", "Confirm") else WS.t("Supprimer la série", "Delete set"), CARD, RED, 13f, 9f) { confirmOr("del") { WS.cmd(this, "del-set", e, s) } }, 6f)
            add(tv(WS.t("Options d'exercice", "Exercise options"), 13f, Color.WHITE, true), 12f)
            add(pill(WS.t("Remplacer l'exercice", "Replace exercise"), CARD, Color.WHITE, 13f, 9f) { swapFor = e; scroll.scrollTo(0, 0); paint() }, 6f)
            add(pill(WS.t("Exercice suivant", "Next exercise"), CARD, Color.WHITE, 13f, 9f) { move(1); page = 1; paint() }, 6f)
        }
        add(pill(if (armed("fin")) WS.t("Confirmer : terminer", "Confirm: finish") else WS.t("Terminer l'entraînement", "Finish workout"), ACC, ON_ACC, 13f, 10f) { confirmOr("fin") { WS.finish(this) } }, 14f)
        add(pill(WS.t("Abandonner", "Discard"), CARD, RED, 13f, 9f) { askAb = true; paint() }, 6f)
    }
}
