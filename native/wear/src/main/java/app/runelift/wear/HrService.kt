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

/* Pendant la séance : mesure le cardio, l'envoie au téléphone et fait vibrer à la fin du repos, écran éteint compris. */
class HrService : Service(), SensorEventListener {
    private var sm: SensorManager? = null
    private var wl: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())
    private var lastSent = 0L
    private val tick = object : Runnable {
        override fun run() {
            WS.checkRest(this@HrService)
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
        } catch (e: Exception) {}
        try {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runelift:workout")
            wl?.acquire(3 * 60 * 60 * 1000L)
        } catch (e: Exception) {}
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onSensorChanged(ev: SensorEvent) {
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

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}
