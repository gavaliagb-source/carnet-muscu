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
    private val hsCb = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val n = try { update.latestMetrics.getData(DataType.STEPS_TOTAL)?.total } catch (e: Exception) { null } ?: return
            stepsHs(n.toInt())
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) { hsOn = false }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }
    private val tick = object : Runnable {
        override fun run() {
            WS.checkRest(this@HrService)
            // Écran éteint, la montre garde les pas en réserve : on les réclame toutes les 5 s.
            if (WS.cl() != null && ++fl % 5 == 0) try { sm?.flush(this@HrService) } catch (e: Exception) {}
            hsCheck()
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
            if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                // Version « réveil » si la montre l'a : les pas arrivent même écran éteint.
                (sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true) ?: sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0) }
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

    private fun hsCheck() {
        val c = WS.cl()
        val want = c != null && c.optInt("st", 0) == 1 && (Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (want && c != null && c.optLong("t0") != hsT0) {
            hsT0 = c.optLong("t0")
            try {
                val ec = HealthServices.getClient(this).exerciseClient
                ec.setUpdateCallback(hsCb)
                val type = if (c.optString("k") == "run") ExerciseType.RUNNING_TREADMILL else ExerciseType.WALKING
                val cfg = ExerciseConfig.builder(type).setDataTypes(setOf(DataType.STEPS_TOTAL)).setIsAutoPauseAndResumeEnabled(false).setIsGpsEnabled(false).build()
                ec.startExerciseAsync(cfg); hsOn = true
            } catch (e: Exception) { hsOn = false }
        } else if (!want && hsOn) hsEnd()
    }
    private fun hsEnd() {
        if (!hsOn) return
        hsOn = false; hsT0 = 0L
        try { val ec = HealthServices.getClient(this).exerciseClient; ec.endExerciseAsync(); ec.clearUpdateCallbackAsync(hsCb) } catch (e: Exception) {}
    }
    private fun stepsHs(n: Int) {
        val c = WS.cl() ?: return
        val now = System.currentTimeMillis()
        if (n != stLast && now - stSent >= 3000) { stLast = n; stSent = now; WS.send(this, JSONObject().put("a", "st").put("n", n).put("t0", c.optLong("t0")).put("t", now)) }
    }

    private fun steps(v: Float) {
        if (hsOn) return
        val c = WS.cl() ?: return
        val t0 = c.optLong("t0")
        if (t0 != stT0 || stBase < 0 || v < stBase) { stT0 = t0; stBase = v; stLast = -1 }
        val n = (v - stBase).toInt()
        val now = System.currentTimeMillis()
        if (n != stLast && now - stSent >= 3000) {
            stLast = n; stSent = now
            WS.send(this, JSONObject().put("a", "st").put("n", n).put("t0", t0).put("t", now))
        }
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
