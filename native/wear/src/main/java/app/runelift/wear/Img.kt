package app.runelift.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/* Images des exercices : téléchargées une fois, gardées sur la montre. */
object Img {
    private val mem = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, url: String, cb: (Bitmap) -> Unit) {
        mem.get(url)?.let { cb(it); return }
        val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
        val f = File(dir, url.hashCode().toString() + ".jpg")
        pool.execute {
            try {
                if (!f.exists() || f.length() == 0L) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 12000
                    c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    c.disconnect()
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1; while (o.outWidth / (sample * 2) >= 96) sample *= 2
                val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@execute
                mem.put(url, b)
                main.post { cb(b) }
            } catch (e: Exception) { try { f.delete() } catch (x: Exception) {} }
        }
    }
}
