package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AwakePeriodsTest {
    // 02:00Z-10:00Z at -04:00 = 22:00-06:00 local, 8 h.
    private fun google(stages: String?, type: String = "STAGES"): JsonObject = Json.parseToJsonElement("""
        {"dataPointName":"users/test/dataTypes/sleep/dataPoints/main","sleep":{
        "interval":{"startTime":"2026-09-15T02:00:00Z","endTime":"2026-09-15T10:00:00Z","startUtcOffset":"-14400s","endUtcOffset":"-14400s"},
        "type":"$type",${stages?.let { "\"stages\":$it," } ?: ""}
        "metadata":{"processed":true,"mainSleep":true},"summary":{"minutesAsleep":"420"}}}
    """).jsonObject
    private fun stage(start: String, end: String, type: String) =
        """{"startTime":"2026-09-15T$start","startUtcOffset":"-14400s","endTime":"2026-09-15T$end","endUtcOffset":"-14400s","type":"$type"}"""
    private fun googleLog(point: JsonObject) = (GoogleHealthParser.sleep(listOf(point)) as SleepFetchResult.Complete).logs.single()

    @Test fun googleAwakeStagesBecomePeriods() {
        val log = googleLog(google("[" + listOf(
            stage("02:00:00Z", "02:10:00Z", "AWAKE"),
            stage("02:10:00Z", "04:00:00Z", "LIGHT"),
            stage("04:00:00Z", "04:12:30Z", "AWAKE"),
            stage("04:12:30Z", "09:58:00Z", "DEEP"),
            stage("09:58:00Z", "10:00:00Z", "AWAKE")).joinToString(",") + "]"))
        assertEquals(listOf(AwakePeriod(0, 600), AwakePeriod(7200, 750), AwakePeriod(28680, 120)), log.awakePeriods)
    }
    @Test fun googleClassicCountsAwakeButNotRestless() {
        val log = googleLog(google("[" + stage("03:00:00Z", "03:05:00Z", "RESTLESS") + "," +
            stage("05:00:00Z", "05:02:00Z", "AWAKE") + "]", type = "CLASSIC"))
        assertEquals(listOf(AwakePeriod(10800, 120)), log.awakePeriods)
    }
    @Test fun googleWithoutStagesKeepsNightWithoutMarkers() {
        val log = googleLog(google(null))
        assertNull(log.awakePeriods)
        assertEquals(480, log.timeInBed)
    }
    @Test fun googleBadStageListDropsOnlyMarkers() {
        val log = googleLog(google("""[{"type":"AWAKE","startTime":"not-a-time"}]"""))
        assertNull(log.awakePeriods)
        assertEquals("2026-09-15", log.dateOfSleep)
        assertEquals(420, log.minutesAsleep)
    }

    private fun fitbit(levels: String?) = """{"sleep":[{"logId":42,"dateOfSleep":"2026-09-15","minutesAsleep":400,"isMainSleep":true,
        "timeInBed":480,"startTime":"2026-09-14T23:00:00.000","endTime":"2026-09-15T07:00:00.000"${levels?.let { ",\"levels\":$it" } ?: ""}}]}"""
    private fun fitbitLog(body: String) = (SleepResponseParser.parse(body) as SleepFetchResult.Complete).logs.single()

    @Test fun fitbitStagesWakeAcrossMidnight() {
        val log = fitbitLog(fitbit("""{"data":[
            {"dateTime":"2026-09-14T23:00:00.000","level":"wake","seconds":300},
            {"dateTime":"2026-09-14T23:05:00.000","level":"light","seconds":3600},
            {"dateTime":"2026-09-15T00:05:00.000","level":"wake","seconds":180},
            {"dateTime":"2026-09-15T00:08:00.000","level":"deep","seconds":1800}],
            "shortData":[{"dateTime":"2026-09-15T01:00:00.000","level":"wake","seconds":30}]}"""))
        assertEquals("42", log.sourceRecordId)
        assertEquals(listOf(AwakePeriod(0, 300), AwakePeriod(3900, 180)), log.awakePeriods)
    }
    @Test fun fitbitClassicAwakeNotRestless() {
        val log = fitbitLog(fitbit("""{"data":[
            {"dateTime":"2026-09-15T01:00:00","level":"restless","seconds":120},
            {"dateTime":"2026-09-15T02:00:00","level":"awake","seconds":60}]}"""))
        assertEquals(listOf(AwakePeriod(10800, 60)), log.awakePeriods)
    }
    @Test fun fitbitMissingOrBadLevels() {
        assertNull(fitbitLog(fitbit(null)).awakePeriods)
        val bad = fitbitLog(fitbit("""{"data":[{"dateTime":"garbage","level":"wake","seconds":60}]}"""))
        assertNull(bad.awakePeriods)
        assertEquals(400, bad.minutesAsleep)
    }

    @Test fun normalizeClampsSortsAndMergesNearWakes() {
        val periods = AwakePeriods.normalize(listOf(500L to 560L, -30L to 60L, 100L to 130L, 7000L to 9999L), 8000)
        // 60->100 is a 40 s gap: merged. 130->500 is not.
        assertEquals(listOf(AwakePeriod(0, 130), AwakePeriod(500, 60), AwakePeriod(7000, 1000)), periods)
        assertEquals(emptyList<AwakePeriod>(), AwakePeriods.normalize(listOf(10L to 10L), 100))
    }
    @Test fun arcAnglesFollowClockTime() {
        // Wedge from 22:00 (60 deg); a 30-min wake 2 h in = 00:00-00:30 = 90..97.5 deg.
        val arc = AwakePeriods.arcs(22.0, 120.0, listOf(AwakePeriod(7200, 1800))).single()
        assertEquals(90f, arc.startDeg, 1e-4f)
        assertEquals(7.5f, arc.sweepDeg, 1e-4f)
    }
    @Test fun shortWakeGetsMinimumLengthAroundItsMiddle() {
        val arc = AwakePeriods.arcs(22.0, 120.0, listOf(AwakePeriod(7200, 60))).single()
        assertEquals(AwakePeriods.MIN_SWEEP_DEG, arc.sweepDeg, 1e-4f)
        assertEquals(90.125f, arc.startDeg + arc.sweepDeg / 2, 1e-4f)
    }
    @Test fun minimumLengthStaysInsideWedgeAndJoinsOverlaps() {
        val arcs = AwakePeriods.arcs(22.0, 120.0, listOf(AwakePeriod(0, 30), AwakePeriod(28770, 30), AwakePeriod(3600, 60), AwakePeriod(3720, 60)))
        assertEquals(3, arcs.size)
        assertEquals(60f, arcs[0].startDeg, 1e-4f)                       // pinned to wedge start
        assertEquals(180f, arcs[2].startDeg + arcs[2].sweepDeg, 1e-4f)   // pinned to wedge end
        // Two 1-min wakes 1 min apart each grow to the minimum and overlap: one arc.
        assertEquals(0.5f + AwakePeriods.MIN_SWEEP_DEG, arcs[1].sweepDeg, 1e-3f)
    }
    @Test fun arcsWrapPastMidnightHours() {
        // A nap starting 23:30 in a wedge that started at hour 23.5: angle keeps increasing past 90.
        val arc = AwakePeriods.arcs(23.5, 30.0, listOf(AwakePeriod(3600, 600))).single()
        assertEquals(97.5f, arc.startDeg, 1e-4f)
    }

    private fun entry(date: String, periods: List<AwakePeriod>?) =
        SleepLogEntry(date, 400, true, 480, "${date}T00:00:00", "${date}T08:00:00", awakePeriods = periods)
    @Test fun cacheKeepsMarkersForNewestThreeNights() {
        val wake = listOf(AwakePeriod(0, 60))
        val dates = (10..15).map { "2026-09-$it" }
        val merged = SleepCache.merge(emptyList(), dates.map { entry(it, wake) }, "2026-09-01", "2026-09-15", true)
        assertEquals(setOf("2026-09-13", "2026-09-14", "2026-09-15"), merged.filter { it.awakePeriods != null }.map { it.dateOfSleep }.toSet())
        assertEquals(6, merged.size)
    }
    @Test fun oldCacheWithoutFieldStillDecodes() {
        val old = """[{"dateOfSleep":"2026-09-15","minutesAsleep":1,"isMainSleep":true,"timeInBed":1,"startTime":"a","endTime":"b"}]"""
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<List<SleepLogEntry>>(old).single().awakePeriods)
    }
}
