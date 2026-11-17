package app.runelift

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/* Pont téléphone <-> montre : envoie l'état de la séance, reçoit les séries cochées et le cardio. */
@CapacitorPlugin(name = "RLWear")
class RLWearPlugin : Plugin(), MessageClient.OnMessageReceivedListener {
    override fun load() {
        try { Wearable.getMessageClient(context).addListener(this) } catch (e: Exception) {}
    }

    override fun handleOnDestroy() {
        try { Wearable.getMessageClient(context).removeListener(this) } catch (e: Exception) {}
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (!e.path.startsWith("/rl/")) return
        val o = try { JSObject(String(e.data, Charsets.UTF_8)) } catch (x: Exception) { JSObject() }
        o.put("path", e.path)
        notifyListeners("cmd", o, true)
    }

    @PluginMethod
    fun setState(call: PluginCall) {
        val j = call.getString("json") ?: "{}"
        try {
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
