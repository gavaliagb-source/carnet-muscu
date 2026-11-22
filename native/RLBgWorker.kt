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

    private fun http(url: String, method: String = "GET", body: String? = null, token: String? = null, json: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 15_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }

    private fun str(f: JSONObject?, k: String) = f?.optJSONObject(k)?.optString("stringValue", "") ?: ""
    private fun num(f: JSONObject?, k: String): Long { val v = f?.optJSONObject(k) ?: return 0L; return v.optString("integerValue", "").toLongOrNull() ?: v.optDouble("doubleValue", 0.0).toLong() }

    override fun doWork(): Result {
        val p = RLLivePlugin.prefs(ctx)
        val c = cfg(ctx) ?: return Result.success()
        try {
            // Économie de lectures Firestore : rien la nuit (minuit à 7 h), les fiches des amis au plus une fois par heure
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour < 7 && p.getBoolean("bgInit", false)) return Result.success()
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
            // Seulement les messages plus récents que le dernier vu : un message déjà lu n'est pas relu (et pas refacturé)
            val inT = p.getString("bgInT", "") ?: ""
            // 10 min de marge : l'heure d'un message vient du téléphone de l'ami, qui peut retarder un peu (les doublons sont écartés par bgSeen)
            val inQ = if (inT.isEmpty()) "" else try { java.time.Instant.parse(inT).minusSeconds(600).toString() } catch (e: Exception) { "" }
            val q = JSONObject().put("structuredQuery", JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", "m")))
                .put("where", JSONObject().put("fieldFilter", JSONObject().put("field", JSONObject().put("fieldPath", "t")).put("op", "GREATER_THAN").put("value", JSONObject().put("stringValue", inQ))))
                .put("limit", 30))
            val (ic, itxt) = http("$base/inbox/$uid:runQuery", "POST", q.toString(), tok, json = true)
            if (ic == 200) {
                val arr = try { JSONArray(itxt) } catch (e: Exception) { JSONArray() }
                val docs = JSONArray(); var maxT = inT
                for (i in 0 until arr.length()) { val d = arr.optJSONObject(i)?.optJSONObject("document") ?: continue; docs.put(d); val t = str(d.optJSONObject("fields"), "t"); if (t > maxT) maxT = t }
                if (maxT != inT) p.edit().putString("bgInT", maxT).apply()
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
            // Raid de la semaine (résumé laissé par l'appli) et la dernière attaque d'un coéquipier
            val ra = c.optJSONObject("ra"); val raWk = ra?.optString("wk") ?: ""
            var hitN = ""; var hitT = 0L
            fun rhSeen(f: JSONObject?, nm: String) {
                val rh = f?.optJSONObject("rh")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return
                val t = num(rh, "t"); if (raWk.isNotEmpty() && str(rh, "wk") == raWk && t > hitT) { hitT = t; hitN = str(f, "name").ifEmpty { nm } }
            }
            val frIds = HashSet<String>()
            val frDue = System.currentTimeMillis() - p.getLong("bgFrT", 0L) > 55 * 60_000L || !p.getBoolean("bgInit", false)
            if (frDue) p.edit().putLong("bgFrT", System.currentTimeMillis()).apply()
            for (i in 0 until friends.length()) friends.optJSONArray(i)?.optString(0)?.takeIf { it.isNotEmpty() }?.let { frIds.add(it) }
            for (i in 0 until if (frDue) minOf(friends.length(), 40) else 0) {
                val a = friends.optJSONArray(i) ?: continue; val fu = a.optString(0); if (fu.isEmpty()) continue
                val (fc, ft) = http("$base/users/$fu?mask.fieldPaths=last&mask.fieldPaths=name&mask.fieldPaths=rh", token = tok)
                if (fc != 200) continue
                val f = JSONObject(ft).optJSONObject("fields") ?: continue
                rhSeen(f, a.optString(1))
                val lf = f.optJSONObject("last")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
                val sid = str(lf, "id"); if (sid.isEmpty() || last.optString(fu) == sid) continue
                last.put(fu, sid)
                val d = str(lf, "d"); val tms = try { java.time.Instant.parse(d).toEpochMilli() } catch (e: Exception) { 0L }
                if (System.currentTimeMillis() - tms > 36 * 3600_000L) continue
                val name = str(f, "name").ifEmpty { a.optString(1, if (fr) "Un ami" else "A friend") }
                val rn = str(lf, "n")
                notes.add((if (fr) "Séance terminée" else "Workout done") to (if (fr) "$name a terminé $rn" else "$name finished $rn"))
            }
            // Coéquipiers de guilde qui ne sont pas mes amis : au plus toutes les 3 h, pour économiser les lectures
            val rm = c.optJSONArray("rm")
            if (ra != null && rm != null && System.currentTimeMillis() - p.getLong("bgRmT", 0L) > 3 * 3600_000L) {
                p.edit().putLong("bgRmT", System.currentTimeMillis()).apply()
                for (i in 0 until minOf(rm.length(), 30)) {
                    val a = rm.optJSONArray(i) ?: continue; val mu = a.optString(0); if (mu.isEmpty() || mu == uid || frIds.contains(mu)) continue
                    val (mc, mt) = http("$base/users/$mu?mask.fieldPaths=rh&mask.fieldPaths=name", token = tok)
                    if (mc == 200) rhSeen(JSONObject(mt).optJSONObject("fields"), a.optString(1))
                }
            }
            var raNote: Pair<String, String>? = null; var raKey = ""
            if (ra != null && ra.optInt("can") == 1) {
                val now = System.currentTimeMillis(); val end = ra.optLong("end"); val mine = ra.optLong("mt"); val left = end - now
                val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val sent = p.getString("bgRaN", "") ?: ""; val bn = ra.optString("bn"); val en = ra.optInt("en")
                val pts = if (fr) "Tu as $en points d'énergie à dépenser contre $bn." else "You have $en energy points to spend on $bn."
                if (left > 0 && hr in 10..21) {
                    if (left <= 48 * 3600_000L) {
                        val k = raWk + if (left <= 24 * 3600_000L) ":1" else ":2"
                        if (!sent.split(',').contains(k)) { raKey = k; raNote = (if (fr) "Plus que ${if (left <= 24 * 3600_000L) "1 jour" else "2 jours"} avant la fin du raid" else "Only ${if (left <= 24 * 3600_000L) "1 day" else "2 days"} left in the raid") to pts }
                    }
                    val today = java.time.LocalDate.now().toString()
                    if (raNote == null && hitT > mine && hitT > p.getLong("bgRaT", 0L) && now - hitT < 36 * 3600_000L && now - mine > 20 * 3600_000L && !sent.split(',').contains("t$today")) {
                        raKey = "t$today"; raNote = (if (fr) "Ton équipe frappe $bn sans toi" else "Your team is hitting $bn without you") to ((if (fr) "${hitN.ifEmpty { "Un coéquipier" }} vient d'attaquer. " else "${hitN.ifEmpty { "A teammate" }} just attacked. ") + pts)
                    }
                }
                if (hitT > p.getLong("bgRaT", 0L) && (raNote != null || first)) p.edit().putLong("bgRaT", hitT).apply()
            }
            if (raNote != null && !first && !RLLivePlugin.fg) {
                RLLivePlugin.channels(ctx)
                val b = NotificationCompat.Builder(ctx, RLLivePlugin.CH_SOCIAL)
                    .setSmallIcon(R.drawable.ic_stat_rl).setContentTitle(raNote.first).setContentText(raNote.second)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(raNote.second))
                    .setContentIntent(RLLivePlugin.openIntent(ctx, "raid", 31)).setAutoCancel(true)
                try { NotificationManagerCompat.from(ctx).notify(NID_BASE + 1, b.build()) } catch (e: SecurityException) {}
                val old = (p.getString("bgRaN", "") ?: "").split(',').filter { it.isNotEmpty() }.takeLast(9)
                p.edit().putString("bgRaN", (old + raKey).joinToString(",")).apply()
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
