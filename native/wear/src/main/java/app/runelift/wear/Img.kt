package app.runelift.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/* Images des exercices : envoyées par le téléphone (comme Hevy), gardées sur la montre.
   Si le téléphone ne les a pas encore envoyées, la montre essaie de les télécharger elle-même. */
object Img {
    @Volatile var version = 0
    @Volatile var ok = 0
    @Volatile var fail = 0
    @Volatile var empty = 0
    const val BASE = "https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/main/images/"
    /* La montre reçoit une clé courte (ex. 0150-eYnzaCm) ; on refait le lien complet, identique à celui du téléphone. */
    fun url(k: String?): String = if (k.isNullOrEmpty()) "" else if (k.startsWith("http")) k else "$BASE$k.jpg"
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val mem = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun key(url: String) = Integer.toHexString(url.hashCode())
    private fun file(ctx: Context, url: String) = File(File(ctx.cacheDir, "img").apply { mkdirs() }, key(url) + ".jpg")

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1; while (o.outWidth / (sample * 2) >= 96) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun fromLayer(ctx: Context, url: String, f: File): Boolean {
        return try {
            val dc = Wearable.getDataClient(ctx)
            val items = Tasks.await(dc.getDataItems(Uri.parse("wear://*/rl/img/" + key(url))), 6, TimeUnit.SECONDS)
            var ok = false
            for (it in items) { if (save(ctx, it, f)) { ok = true; break } }
            items.release(); ok
        } catch (e: Exception) { false }
    }

    private fun save(ctx: Context, item: DataItem, f: File): Boolean {
        val a = DataMapItem.fromDataItem(item).dataMap.getAsset("a") ?: return false
        val r = Tasks.await(Wearable.getDataClient(ctx).getFdForAsset(a), 10, TimeUnit.SECONDS)
        r.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f.length() > 0
    }

    /* Appelé quand le téléphone envoie une image : on la garde et on rafraîchit l'écran. */
    fun received(ctx: Context, item: DataItem) {
        try {
            val url = DataMapItem.fromDataItem(item).dataMap.getString("u") ?: return
            val f = file(ctx, url)
            if (f.exists() && f.length() > 0) return
            if (save(ctx, item, f)) { version++; WS.changed() }
        } catch (e: Exception) {}
    }

    fun load(ctx: Context, key: String, cb: (Bitmap) -> Unit) {
        val url = url(key)
        if (url.isEmpty()) { empty++; return }
        mem.get(url)?.let { cb(it); return }
        val a = ctx.applicationContext
        pool.execute {
            try {
                val f = file(a, url)
                if (!f.exists() || f.length() == 0L) fromLayer(a, url, f)
                // Pas encore reçue : on la demande au téléphone (il l'envoie même si Runelift y est fermé).
                if ((!f.exists() || f.length() == 0L) && asked.add(url)) WS.send(a, org.json.JSONObject().put("a", "img").put("u", url))
                if (!f.exists() || f.length() == 0L) {
                    try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 12000
                        c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        c.disconnect()
                    } catch (e: Exception) { f.delete() }
                }
                if (!f.exists() || f.length() == 0L) { fail++; return@execute }
                val b = decode(f) ?: run { fail++; f.delete(); return@execute }
                ok++
                mem.put(url, b)
                main.post { cb(b) }
            } catch (e: Exception) {}
        }
    }
}
