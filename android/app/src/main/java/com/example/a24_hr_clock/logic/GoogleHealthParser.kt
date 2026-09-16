package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** API instants/offsets stay in the cache; civil strings are a compatibility view for the renderer. */
object GoogleHealthParser {
    private val civilFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun local(value: String, offset: String) = Instant.parse(value).atOffset(
        ZoneOffset.ofTotalSeconds(java.time.Duration.parse("PT$offset".uppercase()).seconds.toInt()))

    fun sleep(points: List<JsonObject>): SleepFetchResult = try {
        var pending = false
        val flags = mutableMapOf<String, Boolean?>()
        val staged = mutableSetOf<String>()
        val logs = points.mapNotNull { point ->
            val sleep = point.getValue("sleep").jsonObject
            val interval = sleep.getValue("interval").jsonObject
            val startInstant = interval.text("startTime")
            val endInstant = interval.text("endTime")
            val startOffset = interval.text("startUtcOffset")
            val endOffset = interval.text("endUtcOffset")
            val start = local(startInstant, startOffset)
            val end = local(endInstant, endOffset)
            val duration = java.time.Duration.between(start, end).toMillis()
            require(duration in 1..604_800_000)
            val metadata = sleep["metadata"]?.jsonObject ?: JsonObject(emptyMap())
            val processed = metadata["processed"]?.jsonPrimitive?.boolean ?: false
            pending = pending || !processed
            val summary = sleep["summary"]?.jsonObject
            if (summary?.get("minutesAsleep") == null && !processed) return@mapNotNull null
            val minutes = summary!!.getValue("minutesAsleep").jsonPrimitive.int
            require(minutes >= 0 && minutes <= (duration + 59_999) / 60_000)
            val name = (point["dataPointName"] ?: point["name"])!!.jsonPrimitive.content
            require(name.contains("/dataTypes/sleep/dataPoints/"))
            val flag = (metadata["mainSleep"] ?: metadata["main"])?.jsonPrimitive?.boolean
            flags[name] = flag
            if (sleep["type"]?.jsonPrimitive?.content == "STAGES") staged.add(name)
            SleepLogEntry(end.toLocalDate().toString(), minutes, flag == true, (duration / 60_000).toInt(),
                start.format(civilFormat), end.format(civilFormat), duration, name,
                provider = "google_health", startInstant = startInstant, endInstant = endInstant,
                startUtcOffset = startOffset, endUtcOffset = endOffset,
                isNap = metadata["nap"]?.jsonPrimitive?.boolean ?: false, processed = processed)
        }
        val inferred = logs.groupBy { it.dateOfSleep }.values.flatMap { day ->
            val unknown = day.filter { flags[it.sourceRecordId] == null }
            if (unknown.isNotEmpty() && day.none { it.isMainSleep }) {
                val candidates = unknown.filter { it.sourceRecordId in staged }.ifEmpty { unknown }
                val main = candidates.maxWith(compareBy({ it.duration }, { it.endInstant }, { it.sourceRecordId }))
                day.map { it.copy(isMainSleep = it.sourceRecordId == main.sourceRecordId) }
            } else day
        }
        if (pending) SleepFetchResult.Pending(inferred, 3000) else SleepFetchResult.Complete(inferred)
    } catch (_: Exception) { SleepFetchResult.Failed(SyncError.DECODING) }

    data class MinuteHeartRate(val instant: Long, val date: String, val point: HeartRatePointEntry)
    fun heartRate(points: List<JsonObject>): List<MinuteHeartRate> {
        val seen = mutableSetOf<Pair<Instant, Int>>()
        val buckets = sortedMapOf<Long, Pair<java.time.OffsetDateTime, MutableList<Int>>>()
        for (point in points) {
            val hr = point.getValue("heartRate").jsonObject
            val sample = hr.getValue("sampleTime").jsonObject
            val time = local(sample.text("physicalTime"), sample.text("utcOffset"))
            val bpm = hr.getValue("beatsPerMinute").jsonPrimitive.int
            require(bpm in 1..399)
            if (!seen.add(time.toInstant() to bpm)) continue
            buckets.getOrPut(Math.floorDiv(time.toEpochSecond(), 60)) { time to mutableListOf() }.second.add(bpm)
        }
        return buckets.map { (minute, value) -> MinuteHeartRate(minute * 1000 * 60, value.first.toLocalDate().toString(),
            HeartRatePointEntry(value.first.toLocalTime().withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm:ss")), value.second.average().roundToInt())) }
    }
}
