package app.runelift

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/* Boutons de la notification de repos : « +30 s » et « Passer ».
   La notification change tout de suite, et la page reçoit l'action (même si elle était en pause). */
class RLLiveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val o = try { JSONObject(RLLivePlugin.prefs(ctx).getString("live", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        when (intent.action) {
            "app.runelift.LIVE_ADD30" -> {
                if (o.optString("mode") == "rest") { o.put("end", maxOf(o.optLong("end"), System.currentTimeMillis()) + 30_000); RLLivePlugin.post(ctx, o) }
                RLLivePlugin.queue(ctx, "add30")
            }
            "app.runelift.LIVE_SKIP" -> {
                if (o.has("wtitle")) RLLivePlugin.post(ctx, JSONObject().put("mode", "work").put("title", o.optString("wtitle")).put("text", o.optString("wtext")).put("start", o.optLong("start")).put("color", o.optInt("color", 0xFF4D9BFF.toInt())))
                RLLivePlugin.queue(ctx, "skip")
            }
        }
    }
}
