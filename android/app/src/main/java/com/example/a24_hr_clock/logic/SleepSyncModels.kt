package com.example.a24_hr_clock.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
enum class SyncError { AUTH_REQUIRED, NETWORK, RATE_LIMIT, SERVER, CONFIGURATION, DECODING }

@Serializable
enum class SleepSyncPhase { UNKNOWN, CHECKING, CURRENT, PROCESSING, ERROR, DISCONNECTED }

@Serializable
data class SleepSyncStatus(
    val lastAttemptAt: Long = 0,
    val lastVerifiedAt: Long = 0,
    val lastDataChangedAt: Long = 0,
    val phase: SleepSyncPhase = SleepSyncPhase.UNKNOWN,
    val error: SyncError? = null,
    val nextRetryAt: Long = 0,
    val retryCount: Int = 0,
    val serverRetryAt: Long = 0,
    val lastSavedAt: Long = 0,
    /** Outcome of the last Connect Google Health tap, kept so a failure outlives its toast and logcat. */
    val connectAttemptAt: Long = 0,
    val connectResult: String = ""
) {
    fun opacity(now: Long, reconnectRequired: Boolean = false): Float = when {
        reconnectRequired || phase == SleepSyncPhase.DISCONNECTED || error == SyncError.AUTH_REQUIRED || lastVerifiedAt == 0L -> .3f
        now - lastVerifiedAt >= 4 * 60 * 60_000L -> .3f
        now - lastVerifiedAt >= 60 * 60_000L -> .6f
        else -> 1f
    }
}

sealed interface SleepFetchResult {
    data class Complete(val logs: List<SleepLogEntry>) : SleepFetchResult
    data class Pending(val partialLogs: List<SleepLogEntry>, val retryAfter: Long) : SleepFetchResult
    data class Failed(val category: SyncError, val retryAt: Long = 0) : SleepFetchResult
}

/** Wire parsing stays here so a later provider can feed the same sync/cache contract. */
object SleepResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(body: String): SleepFetchResult = try {
        val root = json.parseToJsonElement(body).jsonObject
        val response = json.decodeFromString<SleepResponse>(body)
        val logs = response.sleep.orEmpty().mapIndexed { index, log ->
            val raw = root["sleep"]?.let { it as? kotlinx.serialization.json.JsonArray }?.get(index)?.jsonObject
            log.copy(sourceRecordId = raw?.get("logId")?.jsonPrimitive?.content ?: log.sourceRecordId,
                awakePeriods = raw?.let { fitbitAwake(it, log) })
        }
        if (response.meta?.state == "pending") {
            SleepFetchResult.Pending(logs, (response.meta.retryDuration ?: 3000L).coerceAtLeast(1000L))
        } else if (response.sleep != null) {
            SleepFetchResult.Complete(logs)
        } else SleepFetchResult.Failed(SyncError.DECODING)
    } catch (_: Exception) { SleepFetchResult.Failed(SyncError.DECODING) }

    /** `levels.data` wake (stages) or awake (classic) entries; a bad list drops only the markers. */
    private fun fitbitAwake(raw: kotlinx.serialization.json.JsonObject, log: SleepLogEntry): List<AwakePeriod>? = runCatching {
        val data = raw["levels"]?.jsonObject?.get("data") as? kotlinx.serialization.json.JsonArray ?: return@runCatching null
        val start = LocalDateTime.parse(log.startTime)
        val duration = Duration.between(start, LocalDateTime.parse(log.endTime)).seconds
        AwakePeriods.normalize(data.map { it.jsonObject }.filter { AwakePeriods.isAwake(it.getValue("level").jsonPrimitive.content) }.map {
            val s = Duration.between(start, LocalDateTime.parse(it.getValue("dateTime").jsonPrimitive.content)).seconds
            s to s + it.getValue("seconds").jsonPrimitive.content.toLong()
        }, duration)
    }.getOrNull()
}

object SleepCache {
    fun merge(old: List<SleepLogEntry>, incoming: List<SleepLogEntry>, start: String, end: String, complete: Boolean): List<SleepLogEntry> {
        val result = old.filterNot { complete && it.dateOfSleep >= start && it.dateOfSleep <= end }.toMutableList()
        for (log in incoming.filter { it.dateOfSleep >= start && it.dateOfSleep <= end }) {
            result.removeAll {
                it.provider == log.provider && ((log.sourceRecordId != null && it.sourceRecordId == log.sourceRecordId) ||
                    (it.dateOfSleep == log.dateOfSleep && it.startTime == log.startTime && it.endTime == log.endTime))
            }
            result.add(log)
        }
        val cutoff = LocalDate.parse(end).minusDays(14).toString()
        return AwakePeriods.trim(result.filter { it.dateOfSleep >= cutoff }).sortedWith(compareBy({ it.endTime }, { it.startTime }, { it.sourceRecordId })).reversed()
    }
}
