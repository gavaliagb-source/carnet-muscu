package app.runelift

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Téléphone Android :
   - séance et repos sur l'écran verrouillé (notification avec compte à rebours, « +30 s » et « Passer ») ;
   - widget d'écran d'accueil (routine du jour, série de semaines, bouton Commencer) ;
   - vérification des nouvelles de tes amis en arrière-plan, même appli fermée. */
@CapacitorPlugin(name = "RLLive")
class RLLivePlugin : Plugin() {
    companion object {
        @Volatile var instance: RLLivePlugin? = null
        @Volatile var fg = false
        const val P = "rl_live"
        const val NID = 9
        const val CH_LIVE = "rl_live"
        const val CH_END = "rl_rest_end"
        const val CH_SOCIAL = "rl_social"

        fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

        fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val fr = lang(ctx) != "en"
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, if (fr) "Séance et repos" else "Workout and rest", NotificationManager.IMPORTANCE_LOW).apply {
                description = if (fr) "Séance en cours et compte à rebours du repos sur l'écran verrouillé" else "Current workout and rest countdown on the lock screen"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_END, if (fr) "Fin du repos" else "Rest finished", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = longArrayOf(0, 300, 150, 300); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_SOCIAL, if (fr) "Amis" else "Friends", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (fr) "Séances de tes amis, demandes d'ami, défis et encouragements" else "Friends' workouts, friend requests, challenges and cheers"
            })
        }

        fun lang(ctx: Context) = prefs(ctx).getString("lang", "fr") ?: "fr"

        fun openIntent(ctx: Context, extra: String? = null, code: Int = 0): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (extra != null) i.putExtra("rl_start", extra)
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun action(ctx: Context, a: String, code: Int): PendingIntent {
            val i = Intent(ctx, RLLiveReceiver::class.java).setAction("app.runelift.LIVE_$a")
            return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /* Affiche (ou retire) la notification de séance. o = {mode: rest|work|off, title, text, end, start, alert, add, skip} */
        fun post(ctx: Context, o: JSONObject) {
            val nm = NotificationManagerCompat.from(ctx)
            val mode = o.optString("mode", "off")
            if (mode == "off") { nm.cancel(NID); prefs(ctx).edit().remove("live").apply(); return }
            prefs(ctx).edit().putString("live", o.toString()).apply()
            channels(ctx)
            val alert = o.optBoolean("alert", false)
            val b = NotificationCompat.Builder(ctx, if (alert) CH_END else CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat_rl)
                .setContentTitle(o.optString("title", "Runelift"))
                .setContentText(o.optString("text", ""))
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setSilent(!alert)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openIntent(ctx, "open", 1))
                .setColor(o.optInt("color", 0xFF4D9BFF.toInt()))
            if (mode == "rest") {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(o.optLong("end", System.currentTimeMillis())).setShowWhen(true)
                b.addAction(0, o.optString("add", "+30 s"), action(ctx, "ADD30", 2))
                b.addAction(0, o.optString("skip", "Passer"), action(ctx, "SKIP", 3))
            } else {
                val st = o.optLong("start", 0L)
                if (st > 0) b.setUsesChronometer(true).setWhen(st).setShowWhen(true) else b.setShowWhen(false)
            }
            try { nm.notify(NID, b.build()) } catch (e: SecurityException) {} // pas la permission de notifier : rien
        }

        /* Action d'un bouton de la notification : gardée pour la page, et transmise tout de suite si elle écoute. */
        fun queue(ctx: Context, a: String) {
            val p = prefs(ctx)
            val arr = try { JSONArray(p.getString("q", "[]")) } catch (e: Exception) { JSONArray() }
            if (arr.length() < 50) arr.put(JSONObject().put("a", a).put("t", System.currentTimeMillis()))
            p.edit().putString("q", arr.toString()).apply()
            instance?.let { it.notifyListeners("live", JSObject().put("a", a), true); it.clearQueue() }
        }

        fun updateWidgets(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RLWidget::class.java))
            if (ids.isNotEmpty()) RLWidget.render(ctx, mgr, ids)
        }

        @JvmStatic fun takeIntent(ctx: Context, i: Intent?) {
            val v = i?.getStringExtra("rl_start") ?: return
            prefs(ctx).edit().putString("launch", v).putLong("launchT", System.currentTimeMillis()).apply()
            i.removeExtra("rl_start")
        }

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork("rl_bg"); return }
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = PeriodicWorkRequestBuilder<RLBgWorker>(15, TimeUnit.MINUTES).setConstraints(c).build()
            wm.enqueueUniquePeriodicWork("rl_bg", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override fun load() { instance = this; fg = true; channels(context) }
    override fun handleOnResume() { fg = true; NotificationManagerCompat.from(context).cancel(RLBgWorker.NID_BASE) }
    override fun handleOnPause() { fg = false }
    override fun handleOnDestroy() { if (instance === this) instance = null }

    fun clearQueue() { prefs(context).edit().putString("q", "[]").apply() }

    @PluginMethod
    fun live(call: PluginCall) {
        try { post(context, JSONObject(call.data.toString())); call.resolve() } catch (e: Exception) { call.reject(e.message ?: "error") }
    }

    @PluginMethod
    fun pending(call: PluginCall) {
        val p = prefs(context)
        val q = try { JSONArray(p.getString("q", "[]")) } catch (e: Exception) { JSONArray() }
        clearQueue()
        call.resolve(JSObject().put("items", JSArray(q.toString())))
    }

    @PluginMethod
    fun launch(call: PluginCall) {
        val p = prefs(context)
        val v = p.getString("launch", null)
        val fresh = System.currentTimeMillis() - p.getLong("launchT", 0) < 10 * 60_000
        p.edit().remove("launch").apply()
        call.resolve(JSObject().put("start", if (fresh) v else null))
    }

    @PluginMethod
    fun widget(call: PluginCall) {
        prefs(context).edit().putString("widget", call.data.toString()).putString("lang", call.getString("lang", lang(context))).apply()
        updateWidgets(context)
        call.resolve()
    }

    /* Nouvelles des amis en arrière-plan. {on, rt, key, pid, uid, fr:[[uid, nom]], lang} */
    @PluginMethod
    fun bg(call: PluginCall) {
        val on = call.getBoolean("on", false) == true
        val p = prefs(context).edit()
        if (on) {
            val d = JSONObject(call.data.toString())
            val old = RLBgWorker.cfg(context)
            if (old == null || old.optString("uid") != d.optString("uid")) p.putBoolean("bgInit", false) // nouveau compte : on repart sans notifier l'existant
            p.putString("bg", d.toString()).putString("lang", d.optString("lang", "fr")).remove("bgRt")
        } else p.remove("bg")
        p.apply()
        schedule(context, on)
        call.resolve()
    }

    @PluginMethod
    fun bgNow(call: PluginCall) {
        WorkManager.getInstance(context).enqueueUniqueWork("rl_bg_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RLBgWorker>().build())
        call.resolve()
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val nm = NotificationManagerCompat.from(context)
        call.resolve(JSObject().put("notif", nm.areNotificationsEnabled()).put("bg", prefs(context).contains("bg")).put("bgErr", prefs(context).getString("bgErr", ""))
            .put("bgLast", prefs(context).getLong("bgLast", 0)).put("widgets", AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, RLWidget::class.java)).size))
    }
}
