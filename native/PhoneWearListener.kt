package app.runelift

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/* Reçoit les messages de la montre, même appli fermée. Séance lancée sur la montre -> notification, comme Hevy. */
class PhoneWearListener : WearableListenerService() {
    companion object {
        const val NID = 41
        fun clearNotif(ctx: Context) { try { ctx.getSystemService(NotificationManager::class.java).cancel(NID) } catch (e: Exception) {} }
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (!e.path.startsWith("/rl/")) return
        val j = String(e.data, Charsets.UTF_8)
        val a = try { JSONObject(j).optString("a") } catch (x: Exception) { "" }
        val p = RLWearPlugin.instance
        if (p != null) Handler(Looper.getMainLooper()).post { p.deliver(j) }
        else if (a != "hello") RLWearPlugin.queue(this, j)
        if (!RLWearPlugin.fg && (a == "start" || (p == null && a == "done"))) notifyStart()
    }

    private fun notifyStart() {
        try {
            val en = RLWearPlugin.lang(this) == "en"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rl_watch", if (en) "Watch" else "Montre", NotificationManager.IMPORTANCE_HIGH))
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, "rl_watch")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(if (en) "Workout started on your watch" else "Séance lancée sur la montre")
                .setContentText(if (en) "Tap to sync with Runelift" else "Touche pour synchroniser avec Runelift")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NID, n)
        } catch (x: Exception) {}
    }
}
