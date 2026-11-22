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
    companion object { @Volatile var visible = false; @Volatile var demoMode = false }

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
    private var tvCl: TextView? = null; private var tvZ: TextView? = null; private var tvD: TextView? = null; private var tvK: TextView? = null
    private var tvHr: TextView? = null
    private var tvBig: TextView? = null
    private var rotAcc = 0f
    private var lastRot = 0L
    private var streak = 0
    private var dragging = false
    private var fling: Runnable? = null
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
    /* Flèche ‹ › dessinée dans un rond : plus grosse et plus nette qu'un caractère, comme dans Hevy. */
    private fun arrowBtn(dir: Int, desc: String, click: (View) -> Unit): View = object : View(this) {
        private val pb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val pl = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val path = android.graphics.Path()
        override fun onDraw(c: android.graphics.Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f
            pb.color = if (isPressed) Color.parseColor("#3A424E") else CARD
            c.drawCircle(cx, cy, r, pb)
            val a = r * 0.40f; val b = r * 0.20f
            pl.strokeWidth = r * 0.14f
            path.reset(); path.moveTo(cx - dir * b, cy - a); path.lineTo(cx + dir * b, cy); path.lineTo(cx - dir * b, cy + a)
            c.drawPath(path, pl)
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    }.apply {
        contentDescription = desc; isClickable = true; isFocusable = true
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
    /* Image ronde de l'exercice ; tant qu'elle n'est pas là, une lettre de couleur. */
    private fun img(key: String?, name: String, size: Float): View {
        val fr = android.widget.FrameLayout(this)
        fr.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        fr.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3A424E")) }
        fr.clipToOutline = true
        fr.addView(tv(name.trim().take(1).uppercase(), size * 0.42f, ACC, true), android.widget.FrameLayout.LayoutParams(-1, -1))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        fr.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
        if (!key.isNullOrEmpty()) Img.load(this, key) { b -> iv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }; iv.setImageBitmap(b) }
        else Img.empty++
        return fr
    }
    private fun withImg(key: String?, name: String, content: View, size: Float = 44f): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(img(key, name, size))
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
        if (intent?.getBooleanExtra("rl_demo", false) == true) { demoMode = true; WS.demo(this) }
        if (intent?.getBooleanExtra("rl_cardio", false) == true) { demoMode = true; WS.demoCardio(this, intent?.getStringExtra("rl_k") ?: "walk") }
        fromTile(intent)
        scroll = LockScroll(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; isFocusable = true; isFocusableInTouchMode = true }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val w = resources.displayMetrics.widthPixels
            setPadding((w * 0.12).toInt(), (w * 0.15).toInt(), (w * 0.12).toInt(), (w * 0.22).toInt())
            minimumHeight = resources.displayMetrics.heightPixels
        }
        scroll.addView(col)
        setContentView(scroll)
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || !WS.on() || dragging || askAb || swapFor >= 0) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (abs(dx) < dp(45f) || abs(dx) < abs(dy) * 1.4f) return false
                val lo = if (WS.cl() != null) 1 else 0
                val np = if (dx < 0) (page + 1).coerceAtMost(2) else (page - 1).coerceAtLeast(lo)
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); fromTile(intent)
        // Tests sur émulateur seulement : « touche » le bouton qui contient ce texte.
        val t = intent.getStringExtra("rl_tap")
        if (demoMode && !t.isNullOrEmpty()) h.postDelayed({ tapText(t) }, 300)
    }
    private fun tapText(t: String) {
        fun find(v: View): View? {
            if (v is TextView && v.text.toString().contains(t)) return v
            if (v.contentDescription?.toString()?.contains(t) == true) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var v: View? = find(col) ?: return
        while (v != null && !v.isClickable) v = v.parent as? View
        v?.performClick()
    }

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

    /* Flèches ‹ › : série précédente / suivante (pas l'exercice entier). */
    private fun move(d: Int) {
        val n = WS.nEx(); if (n == 0) return
        val all = ArrayList<Pair<Int, Int>>()
        for (e in 0 until n) for (s in 0 until WS.nSets(e)) if (!WS.isDone(e, s)) all.add(e to s)
        if (all.isEmpty()) return
        val c = WS.cur() ?: all[0]
        val i = all.indexOf(c).let { if (it < 0) 0 else it }
        val t = all[((i + d) % all.size + all.size) % all.size]
        WS.exSel = t.first; WS.selE = t.first; WS.selS = t.second; field = 0; paint()
    }

    private fun perms() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission("android.permission.health.READ_HEART_RATE") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.health.READ_HEART_RATE")
        val p = getSharedPreferences("rl", MODE_PRIVATE)
        val actOk = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!actOk && !p.getBoolean("askedAct", false)) { p.edit().putBoolean("askedAct", true).apply(); need.add(Manifest.permission.ACTIVITY_RECOGNITION); requestPermissions(need.toTypedArray(), 3); return }
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
        if (Img.ok == 0) WS.send(this, JSONObject().put("a", "imgs"))
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
        WS.cl()?.let { clUpdate(it) }
        tvClock?.let { set(it, clock(WS.elapsedMs())) }
        tvHr?.let { set(it, hrPrefix + hrLine()) }
        val now = System.currentTimeMillis()
        if (WS.restEnd > now) tvBig?.let { set(it, clock(WS.restEnd - now + 999)) }
        else if (sig.contains("|rest|")) paint()
    }

    private fun set(t: TextView, s: String) { if (t.text.toString() != s) t.text = s }
    private var hrPrefix = ""
    private var lastOn = false
    private fun paint() {
        if (dragging) return
        val on = WS.on()
        if (on != lastOn) { lastOn = on; service(); page = 1; detail = null; askAb = false; swapFor = -1 }
        val now = System.currentTimeMillis()
        val c = WS.cur()
        val resting = on && WS.restEnd > now
        val ns = listOf(on, page, detail, WS.cl()?.optLong("pat") ?: -1L, WS.cl() != null, if (resting) "|rest|" else "|set|", c, field, WS.ver, WS.pending.keys.sorted(), WS.edits.entries.joinToString { it.key + it.value.joinToString() }, if (armed(arm)) arm else "", WS.exSel, Img.version, WS.isLocal(), askAb, swapFor).joinToString("#")
        if (ns == sig) { paintLight(); return }
        sig = ns
        col.removeAllViews(); tvClock = null; tvHr = null; tvBig = null; tvCl = null; tvZ = null; tvD = null; tvK = null; hrPrefix = ""
        col.gravity = if (on && page == 1) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        val sw = resources.displayMetrics.widthPixels; val shh = resources.displayMetrics.heightPixels
        if (on && page == 1 && !askAb && swapFor < 0) col.setPadding((sw * 0.07).toInt(), (shh * 0.04).toInt(), (sw * 0.07).toInt(), (shh * 0.04).toInt())
        else col.setPadding((sw * 0.12).toInt(), (shh * 0.15).toInt(), (sw * 0.12).toInt(), (shh * 0.22).toInt())
        scroll.locked = on && page == 1 && !askAb && swapFor < 0
        if (!on) { if (detail != null) routineDetail(detail!!) else idle(); return }
        if (askAb) { col.gravity = Gravity.CENTER; abandonView(); return }
        if (swapFor >= 0) { swapView(swapFor); return }
        val cl = WS.cl()
        if (cl != null) { if (page < 1) page = 1; col.gravity = Gravity.CENTER; col.setPadding((sw * 0.07).toInt(), (shh * 0.05).toInt(), (sw * 0.07).toInt(), (shh * 0.05).toInt()); scroll.locked = true; clDots(); if (page == 2) clControls(cl) else clView(cl); return }
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


    /* ---------- Cardio en direct (lancé depuis le téléphone) ---------- */
    private fun clMs(c: org.json.JSONObject): Long {
        val pat = c.optLong("pat"); val end = if (pat > 0) pat else System.currentTimeMillis()
        return (end - c.optLong("t0") - c.optLong("pms")).coerceAtLeast(0)
    }
    private fun clZone(b: Int, mx: Int): Pair<String, Int> {
        val f = b.toFloat() / mx.coerceAtLeast(100)
        return when {
            f >= .9f -> "Z5" to Color.parseColor("#ef4444")
            f >= .8f -> "Z4" to Color.parseColor("#fb923c")
            f >= .7f -> "Z3" to Color.parseColor("#facc15")
            f >= .6f -> "Z2" to Color.parseColor("#4ade80")
            else -> "Z1" to Color.parseColor("#7dd3fc")
        }
    }

    private fun clDots() {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 1..2) r.addView(View(this).apply { background = bg(if (i == page) Color.WHITE else Color.parseColor("#4A515C"), 10f) }, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        col.addView(r, lp(-2, -2, 0f))
    }
    /* Page « commandes » : Pause / Reprendre, Terminer, Abandonner */
    private fun clControls(c: org.json.JSONObject) {
        val paused = c.optLong("pat") > 0
        col.addView(pill(if (paused) WS.t("Reprendre", "Resume") else "Pause", CARD, Color.WHITE, 15f, 10f) { WS.clPause(this) }, lp(-2, -2, 0.03f))
        col.addView(pill(if (armed("clfin")) WS.t("Confirmer", "Confirm") else WS.t("Terminer", "Finish"), ACC, ON_ACC, 15f, 10f) { confirmOr("clfin") { WS.clFinish(this) } }, lp(-2, -2, 0.025f))
        col.addView(pill(WS.t("Abandonner", "Discard"), CARD, RED, 13f, 9f) { askAb = true; paint() }, lp(-2, -2, 0.025f))
    }
    private fun clUpdate(c: org.json.JSONObject) {
        val ms = clMs(c); val now = System.currentTimeMillis()
        tvCl?.let { set(it, clock(ms)) }
        tvZ?.let {
            if (WS.bpm > 0 && now - WS.bpmT < 20000) { val z = clZone(WS.bpm, c.optInt("mx", 190)); set(it, "♥ ${WS.bpm}  ${z.first}"); it.setTextColor(z.second) }
            else { set(it, "♥ –"); it.setTextColor(Color.WHITE) }
        }
        val km = c.optDouble("km", 0.0)
        tvD?.let {
            val txt = if (c.optInt("sp", 0) == 1) String.format(java.util.Locale.FRANCE, "%.2f km · %.1f km/h", km, if (km > 0.05) km / (ms / 3600000.0) else 0.0)
            else { val pace = if (km > 0.05) { val pm = ms / 60000.0 / km; String.format("%d:%02d", pm.toInt(), ((pm - pm.toInt()) * 60).toInt()) } else "–"; String.format(java.util.Locale.FRANCE, "%.2f km · %s /km", km, pace) }
            set(it, txt)
        }
        tvK?.let { set(it, "${c.optInt("kc", 0)} kcal") }
    }
    private fun clView(c: org.json.JSONObject) {
        val paused = c.optLong("pat") > 0
        tvCl = tp(clock(clMs(c)), 0.19f, if (paused) MUTED else Color.WHITE, true).also { col.addView(it, lp(-2, -2, 0f)) }
        tvZ = tp("♥ –", 0.10f, Color.WHITE, true).also { col.addView(it, lp(-2, -2, 0.01f)) }
        if (c.optInt("g", 0) == 1) tvD = tp("", 0.05f, Color.WHITE, false).also { col.addView(it, lp(-2, -2, 0.012f)) }
        tvK = tp("", 0.05f, MUTED, false).also { col.addView(it, lp(-2, -2, 0.008f)) }
        col.addView(tp(WS.t("‹ glisse : commandes", "‹ swipe: controls"), 0.04f, MUTED, false), lp(-2, -2, 0.03f))
        clUpdate(c)
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
            add(withImg(e.optString("i"), e.optString("n"), tv(e.optString("n") + if (n > 0) "\n" + WS.t("$n séries", "$n sets") else "", 12f, Color.WHITE).apply { gravity = Gravity.START }), 7f)
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
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); WS.exSel = e; WS.selE = -1; field = 0; page = 1; scroll.scrollTo(0, 0); paint() }
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                addView(tv(x.optString("n"), 13f, Color.WHITE, true).apply { gravity = Gravity.START; maxLines = 2 })
                addView(tv(WS.t("$d/$n séries", "$d/$n sets"), 11f, if (d == n && n > 0) ACC else MUTED).apply { gravity = Gravity.START }) }
            box.addView(withImg(x.optString("i"), x.optString("n"), txt))
            add(box, 7f)
        }
        add(pill(if (armed("fin")) WS.t("Confirmer : terminer", "Confirm: finish") else WS.t("Terminer l'entraînement", "Finish workout"), ACC, ON_ACC, 13f, 9f) { confirmOr("fin") { WS.finish(this) } }, 12f)
        add(pill(WS.t("Abandonner", "Discard"), CARD, RED, 13f, 9f) { askAb = true; paint() }, 6f)
        add(tv(WS.t("Images : ${Img.ok} ok · ${Img.fail} en attente · ${Img.empty} sans lien", "Images: ${Img.ok} ok · ${Img.fail} pending · ${Img.empty} no link"), 9f, Color.parseColor("#5A626E")), 10f)
    }

    /* ---------- Page 1 : série en cours ---------- */
    private fun rest(c: Pair<Int, Int>?) {
        col.addView(tp(WS.t("Repos", "Rest"), 0.055f, MUTED), lp(-2, -2, 0.02f))
        tvBig = tp(clock(WS.restEnd - System.currentTimeMillis() + 999), 0.17f, Color.WHITE, true).also { col.addView(it, lp(-2, -2, 0.01f)) }
        tvHr = tp(hrLine(), 0.05f, HEART, true).also { col.addView(it, lp(-2, -2, 0.01f)) }
        val next = if (c != null) WS.t("Ensuite : ", "Next: ") + (WS.ex(c.first)?.optString("n") ?: "") + (WS.set(c.first, c.second)?.optString("p", "")?.takeIf { it.isNotEmpty() }?.let { WS.t(" · dernière fois : ", " · last time: ") + it } ?: "") else WS.t("Dernière série faite", "Last set done")
        col.addView(marquee(next, 12f, Color.WHITE).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.052f) }, lp((W * 0.72f).toInt(), -2, 0.02f))
        val bh = (W * 0.15f).toInt()
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(tp("+30 s", 0.055f, Color.WHITE, true).apply { background = bg(CARD, 40f); gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); WS.restAdd(this@WatchActivity) } }, LinearLayout.LayoutParams((W * 0.27f).toInt(), bh).apply { rightMargin = (W * 0.025f).toInt() })
            addView(tp(WS.t("Passer", "Skip"), 0.055f, ON_ACC, true).apply { background = bg(ACC, 40f); gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); WS.restSkip(this@WatchActivity) } }, LinearLayout.LayoutParams((W * 0.30f).toInt(), bh).apply { leftMargin = (W * 0.025f).toInt() })
        }, lp(-2, -2, 0.04f))
        if (WS.phoneMode()) col.addView(pill(WS.t("Chrono sur le téléphone", "Timer on phone"), CARD, Color.WHITE, 11f, 6f) { WS.bigTimer(this) }, lp(-2, -2, 0.02f))
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
        col.addView(marquee((x?.optString("n") ?: "") + (st?.optString("p", "")?.takeIf { it.isNotEmpty() }?.let { WS.t(" · dernière fois : ", " · last time: ") + it } ?: ""), 13f, Color.WHITE).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.062f) }, lp((W * 0.70f).toInt(), -2, 0.012f))
        val warm = st?.optString("t") == "W"
        val (dd, nn) = s + 1 to WS.nSets(e)
        hrPrefix = (if (warm) WS.t("Échauf. · ", "Warm-up · ") else "") + "$dd/$nn   "
        tvHr = tp(hrPrefix + hrLine(), 0.048f, Color.WHITE, true).also { col.addView(it, lp(-2, -2, 0.008f)) }
        if (x?.optInt("c", 0) == 1) {
            col.addView(tp(WS.t("Exercice chronométré ou cardio :\nà faire sur le téléphone", "Timed or cardio exercise:\nuse the phone"), 0.038f, MUTED), lp((W * 0.66f).toInt(), -2, 0.03f))
            col.addView(pill(WS.t("Suivant ›", "Next ›"), ACC, ON_ACC, 14f, 8f) { move(1) }, lp(-2, -2, 0.04f))
            return
        }
        val u = WS.st?.optString("u", "lb") ?: "lb"
        fun fmt(f: Int, v: Double) = if (f == 0) (if (v <= 0 && !WS.hasW(e, s)) WS.t("PDC", "BW") else String.format(java.util.Locale.FRANCE, "%.1f", v)) else WS.num(v)
        fun box(label: String, f: Int): LinearLayout {
            val sel = field == f
            val above = tp("", 0.046f, if (sel) ACC else MUTED).apply { alpha = 0.55f }
            val mid = tp("", 0.108f, if (sel) ACC else Color.WHITE, true)
            val below = tp("", 0.046f, if (sel) ACC else MUTED).apply { alpha = 0.55f }
            fun upd() {
                val v = if (f == 0) WS.w(e, s) else WS.r(e, s); val stp = if (f == 0) WS.step() else 1.0
                above.text = if (v - stp >= (if (f == 0) 0.0 else 1.0)) fmt(f, v - stp) else " "
                mid.text = fmt(f, v); below.text = fmt(f, v + stp)
            }
            upd()
            val wheel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = bg(BG, 14f, if (sel) ACC else Color.parseColor("#4A515C"))
                setPadding(0, (W * 0.014f).toInt(), 0, (W * 0.014f).toInt())
                addView(above); addView(mid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (W * 0.008f).toInt(); bottomMargin = (W * 0.008f).toInt() }); addView(below)
            }
            var y0 = 0f; var acc = 0f; var moved = false
            var vt: android.view.VelocityTracker? = null
            wheel.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { fling?.let { h.removeCallbacks(it) }; fling = null; dragging = true; v.parent.requestDisallowInterceptTouchEvent(true); y0 = ev.y; acc = 0f; moved = false; vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev) }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(ev)
                        acc += y0 - ev.y; y0 = ev.y
                        val stepPx = dp(18f).toFloat()
                        while (acc >= stepPx) { acc -= stepPx; moved = true; WS.adjust(e, s, f, 1.0, true); upd(); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        while (acc <= -stepPx) { acc += stepPx; moved = true; WS.adjust(e, s, f, -1.0, true); upd(); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                        val vy = vt?.yVelocity ?: 0f; vt?.recycle(); vt = null
                        if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) { dragging = false; field = f; paint() }
                        else {
                            // Coup de doigt fort = défile vite ; la lunette sert à être précis.
                            val total = (abs(vy) / dp(220f)).toInt().coerceAtMost(22)
                            var n = total
                            val dir = if (vy < 0) 1.0 else -1.0
                            if (n > 0) {
                                // Rapide au début, ralentit à la fin (environ 1 seconde au maximum).
                                val r = object : Runnable { override fun run() {
                                    if (n-- <= 0) { fling = null; dragging = false; field = f; paint(); return }
                                    WS.adjust(e, s, f, dir, true); upd()
                                    if (n % 3 == 0) wheel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    val q = (total - n).toFloat() / total; h.postDelayed(this, 26L + (110f * q * q).toLong())
                                } }
                                fling = r; h.post(r)
                            } else { dragging = false; field = f; paint() }
                        }
                    }
                }
                true
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(tp(label, 0.044f, if (sel) ACC else MUTED, true))
                addView(wheel, LinearLayout.LayoutParams((W * 0.35f).toInt(), -2).apply { topMargin = (W * 0.01f).toInt() })
            }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(box(u.replaceFirstChar { it.uppercase() }, 0), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (W * 0.022f).toInt() })
            addView(box(WS.t("Répétitions", "Reps"), 1), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (W * 0.022f).toInt() })
        }, lp(-2, -2, 0.022f))
        val p = st?.optString("p") ?: ""
        col.addView(tp(if (p.isNotEmpty()) WS.t("précéd. ", "prev. ") + p else " ", 0.044f, MUTED), lp(-2, -2, 0.016f))
        val bd = (W * 0.15f).toInt()
        val done = tp("✓", 0.085f, ON_ACC, true).apply {
            background = bg(ACC, 40f); gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); field = 0; WS.done(this@WatchActivity, e, s) }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(arrowBtn(-1, WS.t("Série précédente", "Previous set")) { move(-1) }, LinearLayout.LayoutParams(bd, bd).apply { rightMargin = (W * 0.03f).toInt() })
            addView(done, LinearLayout.LayoutParams((W * 0.27f).toInt(), (W * 0.15f).toInt()))
            addView(arrowBtn(1, WS.t("Série suivante", "Next set")) { move(1) }, LinearLayout.LayoutParams(bd, bd).apply { leftMargin = (W * 0.03f).toInt() })
        }, lp(-2, -2, 0.022f))
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
        fun item(n: String, l: String?, i: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = bg(CARD, 22f); setPadding(dp(6f), dp(5f), dp(12f), dp(5f)); isClickable = true
                setOnClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.CONFIRM); WS.swap(this@WatchActivity, e, n, l, i); swapFor = -1; page = 1; WS.exSel = e; WS.selE = -1; paint() }
            }
            row.addView(withImg(i, n, tv(n, 12f, Color.WHITE, true).apply { gravity = Gravity.START; maxLines = 2 }, 36f), LinearLayout.LayoutParams(-1, -2))
            add(row, 6f)
        }
        val alt = WS.altOf(e)
        if (alt != null && alt.length() > 0) {
            add(tv(WS.t("Similaires", "Similar"), 12f, ACC, true), 10f)
            for (k in 0 until alt.length()) { val a = alt.optJSONObject(k) ?: continue; val n = a.optString("n"); if (n.isEmpty() || !seen.add(n.lowercase())) continue; item(n, a.optString("l"), a.optString("i")) }
        }
        val mine = WS.mineList().filter { seen.add(it.first.lowercase()) }
        if (mine.isNotEmpty()) {
            add(tv(WS.t("Mes exercices", "My exercises"), 12f, ACC, true), 10f)
            mine.forEach { item(it.first, null, it.second) }
        }
        if ((alt == null || alt.length() == 0) && mine.isEmpty()) add(tv(WS.t("Ouvre Runelift sur le téléphone une fois pour recevoir ta liste d'exercices.", "Open Runelift on the phone once to get your exercise list."), 12f, MUTED), 8f)
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
