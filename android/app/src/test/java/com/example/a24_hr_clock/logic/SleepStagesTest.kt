package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SleepStagesTest {
    private fun google(stages: String) = Json.parseToJsonElement("""
        {"dataPointName":"users/test/dataTypes/sleep/dataPoints/main","sleep":{
        "interval":{"startTime":"2026-09-15T02:00:00Z","endTime":"2026-09-15T10:00:00Z","startUtcOffset":"-14400s","endUtcOffset":"-14400s"},
        "type":"STAGES","stages":$stages,
        "metadata":{"processed":true,"mainSleep":true},"summary":{"minutesAsleep":"420"}}}""").jsonObject
    private fun st(a: String, b: String, t: String) =
        """{"startTime":"2026-09-15T$a","startUtcOffset":"-14400s","endTime":"2026-09-15T$b","endUtcOffset":"-14400s","type":"$t"}"""

    @Test fun googleStagesParseWithShortWakeFolded() {
        val log = (GoogleHealthParser.sleep(listOf(google("[" + listOf(
            st("02:00:00Z", "03:00:00Z", "LIGHT"), st("03:00:00Z", "03:02:00Z", "AWAKE"),
            st("03:02:00Z", "05:00:00Z", "DEEP"), st("05:00:00Z", "05:10:00Z", "AWAKE"),
            st("05:10:00Z", "06:00:00Z", "REM")).joinToString(",") + "]"))) as SleepFetchResult.Complete).logs.single()
        // The 2-minute wake joins the light stage before it; the 10-minute one stays.
        assertEquals(listOf(
            StageSeg(0, 3720, SleepStages.LIGHT), StageSeg(3720, 7080, SleepStages.DEEP),
            StageSeg(10800, 600, SleepStages.WAKE), StageSeg(11400, 3000, SleepStages.REM)), log.stages)
    }

    @Test fun fitbitStagesParseAndClassicMapsToLight() {
        val body = """{"sleep":[{"logId":42,"dateOfSleep":"2026-09-15","minutesAsleep":400,"isMainSleep":true,"timeInBed":420,
            "startTime":"2026-09-14T23:00:00.000","endTime":"2026-09-15T06:00:00.000","duration":25200000,
            "levels":{"data":[{"dateTime":"2026-09-14T23:00:00.000","level":"light","seconds":3600},
            {"dateTime":"2026-09-15T00:00:00.000","level":"deep","seconds":1800},
            {"dateTime":"2026-09-15T00:30:00.000","level":"wake","seconds":300},
            {"dateTime":"2026-09-15T00:35:00.000","level":"rem","seconds":600}],"shortData":[]}}]}"""
        val log = (SleepResponseParser.parse(body) as SleepFetchResult.Complete).logs.single()
        assertEquals(listOf(SleepStages.LIGHT, SleepStages.DEEP, SleepStages.WAKE, SleepStages.REM), log.stages!!.map { it.level })
        assertEquals(StageSeg(5400, 300, SleepStages.WAKE), log.stages!![2])
    }

    @Test fun distancesAreOrderedDeepToWake() {
        val d = listOf(SleepStages.DEEP, SleepStages.LIGHT, SleepStages.REM, SleepStages.WAKE).map { SleepStages.radiusFraction(it) }
        assertEquals(d.sorted(), d)
        assertEquals(0.85, d.last(), 0.0)
    }

    @Test fun curveStaysInRangeStartsAtFirstStageAndIsSmooth() {
        val segs = listOf(StageSeg(0, 3600, SleepStages.DEEP), StageSeg(3600, 3600, SleepStages.WAKE), StageSeg(7200, 3600, SleepStages.LIGHT))
        val pts = SleepStages.curve(segs, 10800)
        assertTrue(pts.all { it.fraction in (0.30 - 1e-9)..(0.85 + 1e-9) })
        assertEquals(0.30, pts.first().fraction, 1e-9)
        assertEquals(0.50, pts.last().fraction, 1e-9)
        // Smoothed: no jump between neighbouring samples as large as the raw 0.55 step.
        assertTrue(pts.zipWithNext().all { (a, b) -> kotlin.math.abs(a.fraction - b.fraction) < 0.3 })
        assertEquals(0, pts.first().offsetSec); assertEquals(10800, pts.last().offsetSec)
    }

    @Test fun noStagesNoCurve() {
        assertTrue(SleepStages.curve(emptyList(), 3600).isEmpty())
    }
}
