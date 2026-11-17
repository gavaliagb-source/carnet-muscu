package app.runelift

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* Toutes les 15 minutes environ (Android choisit le moment), même appli fermée :
   regarde les messages reçus (demandes d'ami, défis, encouragements) et les dernières séances de tes amis,
   puis affiche une notification pour ce qui est nouveau. Lecture seulement, avec ton propre compte. */
class RLBgWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    companion object {
        const val NID_BASE = 3100
        fun cfg(ctx: Context): JSONObject? = try { RLLivePlugin.prefs(ctx).getString("bg", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
    }

    private val ctx get() = applicationContext

    private fun http(url: String, method: String = "GET", body: String? = null, token: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }

    private fun str(f: JSONObject?, k: String) = f?.optJSONObject(k)?.optString("stringValue", "") ?: ""

    override fun doWork(): Result {
        val p = RLLivePlugin.prefs(ctx)
        val c = cfg(ctx) ?: return Result.success()
        try {
            val key = c.optString("key"); val pid = c.optString("pid"); val uid = c.optString("uid")
            var rt = p.getString("bgRt", null) ?: c.optString("rt")
            if (key.isEmpty() || pid.isEmpty() || uid.isEmpty() || rt.isNullOrEmpty()) return Result.success()
            // Jeton d'accès à partir de la connexion gardée par l'appli
            val (tc, tt) = http("https://securetoken.googleapis.com/v1/token?key=$key", "POST", "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"))
            if (tc != 200) { p.edit().putString("bgErr", "token $tc").apply(); return Result.success() }
            val tj = JSONObject(tt); val tok = tj.optString("id_token"); tj.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { rt = it; p.edit().putString("bgRt", it).apply() }
            val base = "https://firestore.googleapis.com/v1/projects/$pid/databases/(default)/documents"
            val first = !p.getBoolean("bgInit", false)
            val fr = RLLivePlugin.lang(ctx) != "en"
            val notes = ArrayList<Pair<String, String>>()

            // 1) Messages reçus
            val seen = try { JSONArray(p.getString("bgSeen", "[]")) } catch (e: Exception) { JSONArray() }
            val seenSet = HashSet<String>(); for (i in 0 until seen.length()) seenSet.add(seen.optString(i))
            val (ic, itxt) = http("$base/inbox/$uid/m?pageSize=30", token = tok)
            if (ic == 200) {
                val docs = JSONObject(itxt).optJSONArray("documents") ?: JSONArray()
                for (i in 0 until docs.length()) {
                    val d = docs.optJSONObject(i) ?: continue; val id = d.optString("name").substringAfterLast('/')
                    if (id.isEmpty() || seenSet.contains(id)) continue
                    seenSet.add(id); seen.put(id)
                    val f = d.optJSONObject("fields"); val n = str(f, "name").ifEmpty { if (fr) "Un ami" else "A friend" }
                    val msg = when (str(f, "type")) {
                        "req" -> (if (fr) "Demande d'ami" else "Friend request") to (if (fr) "$n veut être ton ami" else "$n wants to be your friend")
                        "acc" -> (if (fr) "Nouvel ami" else "New friend") to (if (fr) "$n a accepté ta demande" else "$n accepted your request")
                        "add" -> (if (fr) "Nouvel ami" else "New friend") to (if (fr) "$n t'a ajouté en ami" else "$n added you as a friend")
                        "cheer" -> (if (fr) "Encouragement" else "Cheer") to (if (fr) "$n t'encourage" else "$n cheered you on")
                        "ch" -> (if (fr) "Nouveau défi" else "New challenge") to (if (fr) "$n te lance un défi" else "$n challenged you")
                        "cm" -> (if (fr) "Commentaire" else "Comment") to (if (fr) "$n a commenté ta séance" else "$n commented on your workout")
                        else -> null
                    }
                    if (msg != null) notes.add(msg)
                }
                while (seen.length() > 200) seen.remove(0)
                p.edit().putString("bgSeen", seen.toString()).apply()
            }

            // 2) Dernière séance de chaque ami (petit champ « last » de sa fiche)
            val last = try { JSONObject(p.getString("bgLast2", "{}")) } catch (e: Exception) { JSONObject() }
            val friends = c.optJSONArray("fr") ?: JSONArray()
            for (i in 0 until minOf(friends.length(), 40)) {
                val a = friends.optJSONArray(i) ?: continue; val fu = a.optString(0); if (fu.isEmpty()) continue
                val (fc, ft) = http("$base/users/$fu?mask.fieldPaths=last&mask.fieldPaths=name", token = tok)
                if (fc != 200) continue
                val f = JSONObject(ft).optJSONObject("fields") ?: continue
                val lf = f.optJSONObject("last")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                val sid = str(lf, "id"); if (sid.isEmpty() || last.optString(fu) == sid) continue
                last.put(fu, sid)
                val d = str(lf, "d"); val tms = try { java.time.Instant.parse(d).toEpochMilli() } catch (e: Exception) { 0L }
                if (System.currentTimeMillis() - tms > 36 * 3600_000L) continue
                val name = str(f, "name").ifEmpty { a.optString(1, if (fr) "Un ami" else "A friend") }
                val rn = str(lf, "n")
                notes.add((if (fr) "Séance terminée" else "Workout done") to (if (fr) "$name a terminé $rn" else "$name finished $rn"))
            }
            p.edit().putString("bgLast2", last.toString()).putLong("bgLast", System.currentTimeMillis()).putString("bgErr", "").putBoolean("bgInit", true).apply()

            // 3) Notifications (pas la première fois : on ne réveille pas pour l'ancien ; pas si l'appli est ouverte)
            if (!first && !RLLivePlugin.fg && notes.isNotEmpty()) {
                RLLivePlugin.channels(ctx)
                val nm = NotificationManagerCompat.from(ctx)
                val b = NotificationCompat.Builder(ctx, RLLivePlugin.CH_SOCIAL)
                    .setSmallIcon(R.drawable.ic_stat_rl)
                    .setContentIntent(RLLivePlugin.openIntent(ctx, "friends", 30))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                if (notes.size == 1) b.setContentTitle(notes[0].first).setContentText(notes[0].second)
                else {
                    val st = NotificationCompat.InboxStyle(); notes.take(6).forEach { st.addLine(it.second) }
                    b.setContentTitle(if (fr) "${notes.size} nouvelles de tes amis" else "${notes.size} updates from your friends").setContentText(notes[0].second).setStyle(st)
                }
                try { nm.notify(NID_BASE, b.build()) } catch (e: SecurityException) {}
            }
        } catch (e: Exception) {
            p.edit().putString("bgErr", (e.javaClass.simpleName + " " + (e.message ?: "")).take(120)).apply()
        }
        return Result.success()
    }
}
