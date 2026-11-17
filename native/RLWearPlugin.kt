package app.runelift

import android.content.Context
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray

/* Pont téléphone <-> montre : envoie l'état de la séance, reçoit les séries cochées et le cardio
   (les messages arrivent par PhoneWearListener, même quand l'appli est fermée). */
@CapacitorPlugin(name = "RLWear")
class RLWearPlugin : Plugin() {
    companion object {
        @Volatile var instance: RLWearPlugin? = null
        @Volatile var fg = false
        const val Q = "rl_wear_q"
        fun queue(ctx: Context, j: String) {
            val p = ctx.getSharedPreferences("rl_wear", Context.MODE_PRIVATE)
            val a = try { JSONArray(p.getString(Q, "[]")) } catch (e: Exception) { JSONArray() }
            if (a.length() < 4000) a.put(j)
            p.edit().putString(Q, a.toString()).apply()
        }
        fun lang(ctx: Context) = ctx.getSharedPreferences("rl_wear", Context.MODE_PRIVATE).getString("lang", "fr") ?: "fr"
    }

    override fun load() { instance = this; fg = true; drain() }
    override fun handleOnResume() { fg = true; drain(); PhoneWearListener.clearNotif(context) }
    override fun handleOnPause() { fg = false }
    override fun handleOnDestroy() { if (instance === this) instance = null; fg = false }

    fun deliver(j: String) {
        val o = try { JSObject(j) } catch (x: Exception) { return }
        notifyListeners("cmd", o, true)
    }

    private fun drain() {
        val p = context.getSharedPreferences("rl_wear", Context.MODE_PRIVATE)
        val s = p.getString(Q, null) ?: return
        p.edit().remove(Q).apply()
        val a = try { JSONArray(s) } catch (e: Exception) { return }
        for (i in 0 until a.length()) deliver(a.optString(i))
    }

    @PluginMethod
    fun setState(call: PluginCall) {
        val j = call.getString("json") ?: "{}"
        try {
            val lang = Regex("\"lang\":\"(\\w+)\"").find(j)?.groupValues?.get(1)
            if (lang != null) context.getSharedPreferences("rl_wear", Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
            val req = PutDataMapRequest.create("/rl/state").apply {
                dataMap.putString("j", j)
                dataMap.putLong("t", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(req)
                .addOnSuccessListener { call.resolve() }
                .addOnFailureListener { call.reject(it.message ?: "error") }
        } catch (e: Exception) { call.reject(e.message ?: "error") }
    }

    @PluginMethod
    fun status(call: PluginCall) {
        try {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    val r = JSObject()
                    r.put("nodes", nodes.size)
                    val a = JSArray(); nodes.forEach { a.put(it.displayName) }
                    r.put("names", a)
                    call.resolve(r)
                }
                .addOnFailureListener { call.resolve(JSObject().put("nodes", 0).put("err", it.message ?: "")) }
        } catch (e: Exception) { call.resolve(JSObject().put("nodes", 0).put("err", e.message ?: "")) }
    }
}
