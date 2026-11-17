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
        if (p == null && a == "start") {
            // Appli fermée : la montre continue seule et enverra la séance à la fin.
            try { com.google.android.gms.wearable.Wearable.getMessageClient(this).sendMessage(e.sourceNodeId, "/rl/cmd", "{\"a\":\"nophone\"}".toByteArray()) } catch (x: Exception) {}
            notify(false); return
        }
        if (p != null) Handler(Looper.getMainLooper()).post { p.deliver(j) }
        else if (a != "hello") RLWearPlugin.queue(this, j)
        if (a == "import" && (p == null || !RLWearPlugin.fg)) notify(true)
        else if (!RLWearPlugin.fg && a == "start") notify(false)
    }

    private fun notify(done: Boolean) {
        try {
            val en = RLWearPlugin.lang(this) == "en"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rl_watch", if (en) "Watch" else "Montre", NotificationManager.IMPORTANCE_HIGH))
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, "rl_watch")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(if (done) (if (en) "Watch workout ready" else "Séance de la montre prête") else (if (en) "Workout started on your watch" else "Séance lancée sur la montre"))
                .setContentText(if (done) (if (en) "Tap to add it to Runelift" else "Touche pour l'ajouter à Runelift") else (if (en) "Tap to sync with Runelift" else "Touche pour synchroniser avec Runelift"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NID, n)
        } catch (x: Exception) {}
    }
}
