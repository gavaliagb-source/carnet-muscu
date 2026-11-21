package app.runelift.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/* Reçoit la séance, les images et les messages du téléphone, même quand l'appli de la montre est fermée. */
class WatchListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        WS.init(this)
        for (ev in events) {
            if (ev.type != DataEvent.TYPE_CHANGED) continue
            val path = ev.dataItem.uri.path ?: continue
            if (path.startsWith("/rl/img/")) { Img.received(this, ev.dataItem); continue }
            if (path != "/rl/state") continue
            val wasOn = WS.on()
            WS.update(DataMapItem.fromDataItem(ev.dataItem).dataMap.getString("j") ?: "{}")
            RLTile.refresh(this)
            if (!wasOn && WS.on() && !WatchActivity.visible) notifyStart()
            if (!WS.on()) {
                try { stopService(Intent(this, HrService::class.java)) } catch (e: Exception) {}
                try { getSystemService(NotificationManager::class.java).cancel(5) } catch (e: Exception) {}
            }
        }
    }

    override fun onMessageReceived(e: MessageEvent) {
        WS.init(this)
        if (!e.path.startsWith("/rl/")) return
        val a = try { JSONObject(String(e.data, Charsets.UTF_8)).optString("a") } catch (x: Exception) { "" }
        if (a == "nophone") { WS.noPhone(this); RLTile.refresh(this) }
    }

    private fun notifyStart() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rl_start", "Runelift", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 1, Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, "rl_start")
                .setSmallIcon(R.drawable.ic_ongoing)
                .setContentTitle("Runelift")
                .setContentText(WS.t("Séance lancée · touche pour ouvrir", "Workout started · tap to open"))
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true) // ouvre l'appli tout seule, comme Hevy (sinon : la notification)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(5, n)
        } catch (e: Exception) {}
    }
}
