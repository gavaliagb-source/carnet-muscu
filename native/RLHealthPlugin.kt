package app.runelift

import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/* Pont Runelift <-> Health Connect : envoie les séances (et leurs calories) et lit le poids. */
@CapacitorPlugin(name = "RLHealth")
class RLHealthPlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val contract = PermissionController.createRequestPermissionResultContract()
    private val pWorkout = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val pKcal = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    private val pWeight = HealthPermission.getReadPermission(WeightRecord::class)
    private val pHr = HealthPermission.getReadPermission(HeartRateRecord::class)
    private val pHrW = HealthPermission.getWritePermission(HeartRateRecord::class)
    private val pTcal = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    private val pSteps = HealthPermission.getReadPermission(StepsRecord::class)
    private val perms = setOf(pWorkout, pKcal, pWeight, pHr, pHrW, pTcal, pSteps)

    private fun sdk() = HealthConnectClient.getSdkStatus(context)
    private fun client() = HealthConnectClient.getOrCreate(context)

    private fun PluginCall.safe(block: suspend PluginCall.() -> Unit) {
        val c = this
        scope.launch {
            try { c.block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { c.reject(e.message ?: e.toString()) }
        }
    }

    private fun grantedJs(g: Set<String>): JSArray {
        val a = JSArray()
        if (pWorkout in g) a.put("workout")
        if (pKcal in g) a.put("calories")
        if (pWeight in g) a.put("weight")
        if (pHr in g) a.put("heart")
        if (pHrW in g) a.put("heartw")
        if (pTcal in g) a.put("tcal")
        if (pSteps in g) a.put("steps")
        return a
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val s = sdk()
        val r = JSObject()
        r.put("available", s == HealthConnectClient.SDK_AVAILABLE)
        r.put("ver", 4) // 3 = + pouls et calories totales, 4 = + pas
        r.put("reason", when (s) {
            HealthConnectClient.SDK_AVAILABLE -> "ok"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "missing"
        })
        if (s != HealthConnectClient.SDK_AVAILABLE) { r.put("granted", JSArray()); call.resolve(r); return }
        call.safe { r.put("granted", grantedJs(client().permissionController.getGrantedPermissions())); resolve(r) }
    }

    @PluginMethod
    fun request(call: PluginCall) {
        if (sdk() != HealthConnectClient.SDK_AVAILABLE) { call.reject("unavailable"); return }
        call.safe {
            val g = client().permissionController.getGrantedPermissions()
            if (g.containsAll(perms)) { resolve(JSObject().put("granted", grantedJs(g))); return@safe }
            startActivityForResult(this, contract.createIntent(context, perms), "permDone")
        }
    }

    @ActivityCallback
    private fun permDone(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        call.safe { resolve(JSObject().put("granted", grantedJs(client().permissionController.getGrantedPermissions()))) }
    }

    @PluginMethod
    fun saveWorkout(call: PluginCall) {
        if (sdk() != HealthConnectClient.SDK_AVAILABLE) { call.reject("unavailable"); return }
        val id = call.getString("id") ?: run { call.reject("id"); return }
        val start = Instant.parse(call.getString("start") ?: run { call.reject("start"); return })
        var end = Instant.parse(call.getString("end") ?: run { call.reject("end"); return })
        if (!end.isAfter(start)) end = start.plusSeconds(60)
        val title = call.getString("title")
        val kcal = call.getDouble("kcal") ?: 0.0
        val bpm = call.getArray("bpm") // [[secondes depuis le début, bpm], ...] mesurés par la montre
        call.safe {
            val c = client()
            val g = c.permissionController.getGrantedPermissions()
            val rules = ZoneId.systemDefault().rules
            val so = rules.getOffset(start)
            val eo = rules.getOffset(end)
            val recs = mutableListOf<Record>()
            if (pWorkout in g) recs.add(ExerciseSessionRecord(
                startTime = start, startZoneOffset = so, endTime = end, endZoneOffset = eo,
                metadata = Metadata.manualEntry(clientRecordId = "rl-w-$id", clientRecordVersion = 2L),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING, // Samsung Health affiche « Musculation » (l'entraînement de force y devient « Autre entraînement »)
                title = title))
            if (kcal > 0 && pKcal in g) recs.add(ActiveCaloriesBurnedRecord(
                startTime = start, startZoneOffset = so, endTime = end, endZoneOffset = eo,
                energy = Energy.kilocalories(kcal),
                metadata = Metadata.manualEntry(clientRecordId = "rl-c-$id", clientRecordVersion = 2L)))
            if (kcal > 0 && pTcal in g) recs.add(TotalCaloriesBurnedRecord(
                startTime = start, startZoneOffset = so, endTime = end, endZoneOffset = eo,
                energy = Energy.kilocalories(kcal),
                metadata = Metadata.manualEntry(clientRecordId = "rl-t-$id", clientRecordVersion = 2L)))
            if (bpm != null && bpm.length() > 0 && pHrW in g) {
                val samples = mutableListOf<HeartRateRecord.Sample>()
                for (i in 0 until bpm.length()) {
                    try {
                        val p = bpm.getJSONArray(i)
                        val t = start.plusSeconds(p.getLong(0)); val b = p.getLong(1)
                        if (b in 26..249 && !t.isAfter(end) && !t.isBefore(start)) samples.add(HeartRateRecord.Sample(time = t, beatsPerMinute = b))
                    } catch (x: Exception) {}
                }
                if (samples.isNotEmpty()) recs.add(HeartRateRecord(
                    startTime = samples.first().time, startZoneOffset = so,
                    endTime = samples.last().time.plusSeconds(1), endZoneOffset = eo,
                    samples = samples.take(2500),
                    metadata = Metadata.manualEntry(clientRecordId = "rl-h-$id", clientRecordVersion = 2L)))
            }
            if (recs.isEmpty()) { reject("no_permission"); return@safe }
            c.insertRecords(recs)
            resolve(JSObject().put("ok", true).put("count", recs.size))
        }
    }

    @PluginMethod
    fun readWeight(call: PluginCall) {
        if (sdk() != HealthConnectClient.SDK_AVAILABLE) { call.reject("unavailable"); return }
        val days = (call.getInt("days") ?: 30).toLong()
        call.safe {
            val c = client()
            if (pWeight !in c.permissionController.getGrantedPermissions()) { reject("no_permission"); return@safe }
            val res = c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.after(Instant.now().minusSeconds(days * 86400))))
            val a = JSArray()
            for (w in res.records) {
                if (w.metadata.dataOrigin.packageName == context.packageName) continue
                a.put(JSObject().put("t", w.time.toString()).put("kg", w.weight.inKilograms))
            }
            resolve(JSObject().put("items", a))
        }
    }

    @PluginMethod
    fun readHeartRate(call: PluginCall) {
        if (sdk() != HealthConnectClient.SDK_AVAILABLE) { call.reject("unavailable"); return }
        val start = Instant.parse(call.getString("start") ?: run { call.reject("start"); return })
        val end = Instant.parse(call.getString("end") ?: run { call.reject("end"); return })
        call.safe {
            val c = client()
            if (pHr !in c.permissionController.getGrantedPermissions()) { reject("no_permission"); return@safe }
            val a = JSArray()
            var token: String? = null
            do {
                val res = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token))
                for (r in res.records) for (x in r.samples) {
                    if (x.time.isBefore(start) || x.time.isAfter(end)) continue
                    a.put(JSObject().put("t", x.time.toString()).put("bpm", x.beatsPerMinute))
                }
                token = res.pageToken
            } while (token != null && a.length() < 20000)
            resolve(JSObject().put("items", a))
        }
    }

    /* Pas faits entre start et end, toutes sources confondues (montre + téléphone comptés une seule fois par Health Connect) */
    @PluginMethod
    fun readSteps(call: PluginCall) {
        if (sdk() != HealthConnectClient.SDK_AVAILABLE) { call.reject("unavailable"); return }
        val start = Instant.parse(call.getString("start") ?: run { call.reject("start"); return })
        val end = Instant.parse(call.getString("end") ?: run { call.reject("end"); return })
        call.safe {
            val c = client()
            if (pSteps !in c.permissionController.getGrantedPermissions()) { reject("no_permission"); return@safe }
            val r = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))
            resolve(JSObject().put("steps", r[StepsRecord.COUNT_TOTAL] ?: 0L))
        }
    }

    @PluginMethod
    fun openSettings(call: PluginCall) {
        try {
            val i = android.content.Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            call.resolve()
        } catch (e: Exception) {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                call.resolve()
            } catch (e2: Exception) { call.reject(e2.message ?: "error") }
        }
    }
}
