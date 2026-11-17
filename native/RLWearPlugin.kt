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
import com.google.android.gms.wearable.Asset
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

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
        /* Télécharge une image d'exercice, la réduit et l'envoie à la montre. */
        fun pushImage(ctx: Context, u: String): Boolean {
            if (!u.startsWith("https://")) return false
            return try {
                val k = Integer.toHexString(u.hashCode())
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val raw = c.inputStream.use { it.readBytes() }; c.disconnect()
                val b0 = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return false
                val sz = 112; val sc = sz.toFloat() / maxOf(b0.width, b0.height)
                val b = if (sc < 1f) Bitmap.createScaledBitmap(b0, (b0.width * sc).toInt().coerceAtLeast(1), (b0.height * sc).toInt().coerceAtLeast(1), true) else b0
                val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 82, out)
                val req = PutDataMapRequest.create("/rl/img/$k").apply {
                    dataMap.putAsset("a", Asset.createFromBytes(out.toByteArray()))
                    dataMap.putString("u", u)
                    dataMap.putLong("t", System.currentTimeMillis())
                }.asPutDataRequest()
                com.google.android.gms.tasks.Tasks.await(Wearable.getDataClient(ctx).putDataItem(req))
                true
            } catch (e: Exception) { false }
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

    /* Images des exercices : le téléphone les télécharge et les envoie à la montre (une seule fois chacune). */
    @PluginMethod
    fun sendImages(call: PluginCall) {
        val arr = call.getArray("urls") ?: JSArray()
        val urls = (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("https://") }
        call.resolve()
        val ctx = context.applicationContext
        Thread {
            for (u in urls) pushImage(ctx, u)
        }.start()
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
