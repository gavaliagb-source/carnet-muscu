package app.runelift

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONObject

/* Widget Runelift : routine du jour, série de semaines et un bouton pour commencer.
   Les textes viennent de l'appli (RLLive.widget) et sont gardés sur le téléphone. */
class RLWidget : AppWidgetProvider() {
    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val d = try { JSONObject(RLLivePlugin.prefs(ctx).getString("widget", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
            val fr = RLLivePlugin.lang(ctx) != "en"
            for (id in ids) {
                val v = RemoteViews(ctx.packageName, R.layout.rl_widget)
                v.setTextViewText(R.id.rlw_head, d.optString("head", "Runelift"))
                v.setTextViewText(R.id.rlw_title, d.optString("title", if (fr) "Ouvre Runelift" else "Open Runelift"))
                v.setTextViewText(R.id.rlw_sub, d.optString("sub", ""))
                v.setTextViewText(R.id.rlw_btn, d.optString("btn", if (fr) "Ouvrir" else "Open"))
                val acc = d.optInt("acc", 0)
                if (acc != 0 && Build.VERSION.SDK_INT >= 31) {
                    v.setColorStateList(R.id.rlw_btn, "setBackgroundTintList", ColorStateList.valueOf(acc))
                    v.setTextColor(R.id.rlw_btn, d.optInt("onAcc", 0xFF0D0F12.toInt()))
                }
                val start = d.optString("start", "open").ifEmpty { "open" }
                v.setOnClickPendingIntent(R.id.rlw_btn, RLLivePlugin.openIntent(ctx, start, 20))
                v.setOnClickPendingIntent(R.id.rlw_root, RLLivePlugin.openIntent(ctx, "open", 21))
                mgr.updateAppWidget(id, v)
            }
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)
}
