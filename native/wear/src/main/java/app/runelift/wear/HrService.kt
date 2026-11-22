package app.runelift.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate

/* Pendant la séance : mesure le cardio, l'envoie au téléphone et fait vibrer à la fin du repos, écran éteint compris. */
class HrService : Service(), SensorEventListener {
    private var sm: SensorManager? = null
    private var wl: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())
    private var lastSent = 0L
    private var stBase = -1f
    private var stT0 = 0L
    private var stLast = -1
    private var stSent = 0L
    private var fl = 0
    // Health Services : comme Samsung Health, la montre suit l'exercice et compte les pas même écran éteint.
    private var hsT0 = 0L
    private var hsOn = false
    private var nUp = 0
    private val hsCb = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (++nUp <= 3 || nUp % 20 == 0) android.util.Log.i("RLSTEP", "maj #$nUp etat=${try { update.exerciseStateInfo.state.name } catch (e: Throwable) { "?" }} types=${try { update.latestMetrics.dataTypes.joinToString { it.name } } catch (e: Throwable) { e.toString() }}")
            try { update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { hrHs(it.toInt()) } } catch (e: Throwable) { android.util.Log.i("RLSTEP", "err pouls $e") }
            val n = try { update.latestMetrics.getData(DataType.STEPS_TOTAL)?.total } catch (e: Throwable) { null }
            if (n != null) { stepsHs(n.toInt()); return }
            // Plan B : certaines montres donnent les pas par intervalle au lieu du total
            val d = try { update.latestMetrics.getData(DataType.STEPS).sumOf { it.value } } catch (e: Throwable) { 0L }
            if (d > 0) { hsAcc += d; stepsHs(hsAcc.toInt()) }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() { android.util.Log.i("RLSTEP", "rappel enregistré") }
        override fun onRegistrationFailed(throwable: Throwable) { hsOn = false; dbg("suivi refusé : " + (throwable.message ?: "").take(100)) }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) { android.util.Log.i("RLSTEP", "dispo ${dataType.name} $availability") }
    }
    private val tick = object : Runnable {
        override fun run() {
            WS.checkRest(this@HrService)
            // Écran éteint, la montre garde les pas en réserve : on les réclame toutes les 5 s.
            fl++
            if (WS.cl() != null && fl % 5 == 0) try { sm?.flush(this@HrService) } catch (e: Exception) {}
            try { hsCheck() } catch (e: Throwable) {}
            if (WS.cl() != null) { stepReg(); diag() }
            // Écran éteint, Health Services regroupe les données : on les réclame toutes les 10 s.
            if (hsOn && fl % 10 == 0 && scr() == "off") try { HealthServices.getClient(this@HrService).exerciseClient.flushAsync() } catch (e: Throwable) {}
            if (!WS.on()) { stopSelf(); return }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WS.init(this)
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rl_live", "Runelift", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val nb = NotificationCompat.Builder(this, "rl_live")
                .setSmallIcon(R.drawable.ic_ongoing)
                .setContentTitle("Runelift")
                .setContentText(WS.t("Séance en cours", "Workout in progress"))
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setOngoing(true)
                .setContentIntent(pi)
            // Icône Runelift en bas du cadran tant que la séance est en cours (comme Hevy).
            try {
                val status = androidx.wear.ongoing.Status.Builder()
                    .addTemplate("#t#")
                    .addPart("t", androidx.wear.ongoing.Status.StopwatchPart(android.os.SystemClock.elapsedRealtime() - WS.elapsedMs()))
                    .build()
                androidx.wear.ongoing.OngoingActivity.Builder(applicationContext, 7, nb)
                    .setStaticIcon(R.drawable.ic_ongoing)
                    .setTouchIntent(pi)
                    .setStatus(status)
                    .build()
                    .apply(applicationContext)
            } catch (e: Exception) {}
            val n = nb.build()
            ServiceCompat.startForeground(this, 7, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: Exception) { stopSelf(); return }
        try {
            sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            // Pas (cardio sur tapis) : le téléphone en déduit la distance et la vitesse.
            stepReg()
        } catch (e: Exception) {}
        try {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runelift:workout")
            wl?.acquire(3 * 60 * 60 * 1000L)
        } catch (e: Exception) {}
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onSensorChanged(ev: SensorEvent) {
        if (ev.sensor.type == Sensor.TYPE_STEP_COUNTER && ev.values.isNotEmpty()) { steps(ev.values[0]); return }
        if (ev.sensor.type != Sensor.TYPE_HEART_RATE || ev.values.isEmpty()) return
        val b = ev.values[0].toInt()
        if (b < 30 || b > 230) return
        val now = System.currentTimeMillis()
        WS.bpm = b; WS.bpmT = now; WS.changed()
        if (now - lastSent >= 5000) {
            lastSent = now
            WS.hr(this, b, now)
        }
    }

    private var snReg = 0 // 0 = pas encore, 1 = branché, -1 = la montre n'a pas de capteur de pas
    private fun actOk() = Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    // Version « réveil » si la montre l'a : les pas arrivent même écran éteint. Rebranché si la permission arrive pendant le cardio.
    private fun stepReg() {
        if (snReg != 0 || !actOk()) return
        try { val sn = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true) ?: sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            snReg = if (sn != null && sm?.registerListener(this, sn, SensorManager.SENSOR_DELAY_NORMAL, 0) == true) 1 else -1 } catch (e: Throwable) { snReg = -1 }
    }
    // Diagnostic affiché sur l'écran de cardio du téléphone : quel compteur marche, et combien il a compté
    private var lastErr = ""; private var diagT = 0L
    private fun diag() {
        val c = WS.cl() ?: return; if (c.optInt("st", 0) != 1) return
        val now = System.currentTimeMillis(); if (now - diagT < 10_000) return; diagT = now
        val fr = WS.t("a", "b") == "a"
        val m = if (!actOk()) (if (fr) "permission Activité physique refusée : Paramètres de la montre → Applis → Runelift → Autorisations" else "Physical activity permission denied: watch Settings → Apps → Runelift → Permissions")
            else (if (fr) "suivi ${if (hsOn) "actif $hsN" else "inactif"} · capteur ${when (snReg) { 1 -> "$snN"; -1 -> "absent"; else -> "en attente" }}" else "tracking ${if (hsOn) "on $hsN" else "off"} · sensor ${when (snReg) { 1 -> "$snN"; -1 -> "missing"; else -> "waiting" }}") + (if (lastErr.isNotEmpty()) " · $lastErr" else "")
        try { WS.send(this, JSONObject().put("a", "dbg").put("m", m)) } catch (e: Throwable) {}
    }
    private fun scr(): String = try { if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) "on" else "off" } catch (e: Throwable) { "?" }
    private fun dbg(m: String) { android.util.Log.i("RLSTEP", "dbg $m"); if (!m.startsWith("exercice montre démarré")) lastErr = m.take(80); try { WS.send(this, JSONObject().put("a", "dbg").put("m", m)) } catch (e: Throwable) {} }
    private fun hsCheck() {
        val c = WS.cl()
        val act = Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val want = c != null && c.optInt("st", 0) == 1 && act
        if (c != null && c.optInt("st", 0) == 1 && !act && hsT0 != -1L) { hsT0 = -1L; dbg("permission Activité physique refusée sur la montre") }
        if (want && c != null && c.optLong("t0") != hsT0) {
            hsT0 = c.optLong("t0")
            try {
                val ec = HealthServices.getClient(this).exerciseClient
                ec.setUpdateCallback(hsCb)
                val type = if (c.optString("k") == "run") ExerciseType.RUNNING_TREADMILL else ExerciseType.WALKING
                hsAcc = 0L; hsOn = true
                // Essais dans l'ordre : total de pas, pas par intervalle, pouls seul (le capteur de pas prend alors le relais)
                val tries = listOf<Set<DataType<*, *>>>(setOf(DataType.STEPS_TOTAL, DataType.HEART_RATE_BPM), setOf(DataType.STEPS, DataType.HEART_RATE_BPM), setOf(DataType.HEART_RATE_BPM))
                fun go(i: Int) {
                    val cfg = ExerciseConfig.builder(type).setDataTypes(tries[i]).setIsAutoPauseAndResumeEnabled(false).setIsGpsEnabled(false).build()
                    val f = ec.startExerciseAsync(cfg)
                    f.addListener({
                        try { f.get(); dbg("exercice montre démarré" + if (i > 0) " (essai ${i + 1})" else "") }
                        catch (e: Throwable) { if (i + 1 < tries.size) go(i + 1) else { hsOn = false; dbg("exercice refusé : " + (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(120)) } }
                    }, mainExecutor)
                }
                go(0)
            } catch (e: Throwable) { hsOn = false; dbg("Health Services indisponible : " + (e.message ?: e.javaClass.simpleName).take(120)) }
        } else if (!want && hsOn) hsEnd()
    }
    private fun hsEnd() {
        if (!hsOn) return
        hsOn = false; hsT0 = 0L
        try { val ec = HealthServices.getClient(this).exerciseClient; ec.endExerciseAsync(); ec.clearUpdateCallbackAsync(hsCb) } catch (e: Throwable) {}
    }
    private fun hrHs(b: Int) {
        if (lastSent == 0L) android.util.Log.i("RLSTEP", "pouls hs $b")
        if (b < 30 || b > 230) return
        val now = System.currentTimeMillis()
        WS.bpm = b; WS.bpmT = now; WS.changed()
        if (now - lastSent >= 5000) { lastSent = now; WS.hr(this, b, now) }
    }
    private var hsN = 0; private var hsNT0 = 0L; private var snN = 0; private var hsAcc = 0L
    private fun stepsHs(n: Int) {
        val c = WS.cl() ?: return
        if (c.optLong("t0") != hsNT0) { hsNT0 = c.optLong("t0"); hsN = 0 }
        hsN = maxOf(hsN, n); stSend(c)
    }
    // Les deux compteurs tournent : celui qui a compté le plus gagne (l'un des deux peut décrocher écran éteint)
    private fun stSend(c: JSONObject) {
        val n = maxOf(hsN, snN); val now = System.currentTimeMillis()
        if (n != stLast && now - stSent >= 3000) { stLast = n; stSent = now; android.util.Log.i("RLSTEP", "send n=$n hs=$hsN capteur=$snN ecran=${scr()}"); WS.send(this, JSONObject().put("a", "st").put("n", n).put("t0", c.optLong("t0")).put("t", now)) }
    }

    private fun steps(v: Float) {
        val c = WS.cl() ?: return
        val t0 = c.optLong("t0")
        if (t0 != stT0 || stBase < 0 || v < stBase) { stT0 = t0; stBase = v; stLast = -1; snN = 0; if (t0 != hsNT0) { hsNT0 = t0; hsN = 0 } }
        snN = maxOf(snN, (v - stBase).toInt()); stSend(c)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        h.removeCallbacks(tick)
        hsEnd()
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
