package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

interface CredentialProvider { suspend fun accessToken(rejectedToken: String? = null): String }
interface HealthProvider {
    suspend fun identity(): HealthIdentity
    suspend fun sleep(start: String, end: String): SleepFetchResult
    suspend fun heartRate(start: String, end: String): List<GoogleHealthParser.MinuteHeartRate>
}
data class HealthIdentity(val healthUserId: String, val legacyUserId: String)

class GoogleHealthClient(
    private val credentials: CredentialProvider,
    private val defer: suspend (Long) -> Unit = {},
    private val deadline: suspend () -> Long = { 0 },
    private val requireReconnect: suspend () -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val base: String = "https://health.googleapis.com/v4",
    private val now: () -> Long = System::currentTimeMillis,
    /** Receives field names only (never values) so the stage payload can be checked on a device. */
    private val shapeLog: (String) -> Unit = {}
) : HealthProvider {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun request(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val retry = deadline()
        if (retry > now()) throw FitbitRequestException(SyncError.RATE_LIMIT, retry)
        var token = credentials.accessToken()
        repeat(2) { attempt ->
            val url = (base + path).toHttpUrl().newBuilder().apply { params.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
            val response = try {
                client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).awaitResponse()
                    .use { FitbitHttpResult(it.code, it.body?.string().orEmpty(), it.headers) }
            } catch (_: IOException) { throw FitbitRequestException(SyncError.NETWORK) }
            if (response.code == 401 && attempt == 0) {
                token = credentials.accessToken(token)
            } else {
                if (response.code !in 200..299) {
                    val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                    val missingScope = runCatching { root?.get("error")?.jsonObject?.get("details")?.jsonArray?.any {
                        it.jsonObject["reason"]?.jsonPrimitive?.content == "ACCESS_TOKEN_SCOPE_INSUFFICIENT"
                    } == true }.getOrDefault(false)
                    val category = when {
                        response.code == 429 -> SyncError.RATE_LIMIT
                        response.code >= 500 -> SyncError.SERVER
                        response.code == 401 || missingScope -> SyncError.AUTH_REQUIRED
                        else -> SyncError.CONFIGURATION
                    }
                    var retryAt = 0L
                    if (category == SyncError.RATE_LIMIT) {
                        val header = response.headers["Retry-After"]
                        retryAt = header?.toLongOrNull()?.let { now() + it.coerceIn(1, 604800) * 1000 }
                            ?: runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                            ?: (now() + 60_000)
                        retryAt = maxOf(now() + 1000, retryAt)
                        defer(retryAt)
                    }
                    if (category == SyncError.AUTH_REQUIRED) requireReconnect()
                    throw FitbitRequestException(category, retryAt)
                }
                return try { json.parseToJsonElement(response.body).jsonObject }
                catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }
            }
        }
        throw FitbitRequestException(SyncError.AUTH_REQUIRED)
    }

    override suspend fun identity(): HealthIdentity {
        val result = request("/users/me/identity")
        return try {
            HealthIdentity(result.getValue("healthUserId").jsonPrimitive.content.also { require(it.isNotBlank()) },
                result.getValue("legacyUserId").jsonPrimitive.content)
        } catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }
    }

    suspend fun points(type: String, field: String, start: String, endExclusive: String): List<JsonObject> {
        val params = mutableMapOf("dataSourceFamily" to "users/me/dataSourceFamilies/google-sources",
            "filter" to "$field >= \"$start\" AND $field < \"$endExclusive\"", "pageSize" to if (type == "sleep") "25" else "10000")
        val result = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        while (true) {
            val page = request("/users/me/dataTypes/$type/dataPoints:reconcile", params)
            val next = try {
                result += page["dataPoints"]?.jsonArray.orEmpty().map { it.jsonObject }
                page["nextPageToken"]?.jsonPrimitive?.content.orEmpty()
            } catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }
            if (next.isEmpty()) return result
            if (!seen.add(next)) throw FitbitRequestException(SyncError.DECODING)
            params["pageToken"] = next
        }
    }

    override suspend fun sleep(start: String, end: String): SleepFetchResult = try {
        val points = points("sleep", "sleep.interval.civil_end_time", start, LocalDate.parse(end).plusDays(1).toString())
        runCatching { shapeLog(sleepShape(points)) }
        GoogleHealthParser.sleep(points)
    } catch (e: FitbitRequestException) { SleepFetchResult.Failed(e.category, e.retryAt) }

    private fun sleepShape(points: List<JsonObject>): String {
        val sleeps = points.mapNotNull { it["sleep"] as? JsonObject }
        fun keys(items: List<JsonObject>) = items.flatMap { it.keys }.toSortedSet().joinToString(",")
        val stages = sleeps.flatMap { (it["stages"] as? JsonArray).orEmpty().mapNotNull { s -> s as? JsonObject } }
        val shorts = sleeps.flatMap { (it["shortAwakenings"] as? JsonArray).orEmpty().mapNotNull { s -> s as? JsonObject } }
        return "points=${points.size} sleep{${keys(sleeps)}} stages[]{${keys(stages)}} shortAwakenings[]{${keys(shorts)}}"
    }

    override suspend fun heartRate(start: String, end: String): List<GoogleHealthParser.MinuteHeartRate> = try {
        GoogleHealthParser.heartRate(points("heart-rate", "heart_rate.sample_time.physical_time", start, end))
    } catch (e: FitbitRequestException) { throw e }
      catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }

    suspend fun dailyValue(type: String, field: String, valueField: String, date: String): Double? {
        val points = points(type, "${type.replace('-', '_')}.date", date, LocalDate.parse(date).plusDays(1).toString())
        return try { points.firstOrNull()?.getValue(field)?.jsonObject?.get(valueField)?.jsonPrimitive?.double }
        catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }
    }
}
