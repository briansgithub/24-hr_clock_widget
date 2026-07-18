package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class BedtimeNotificationManagerTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Afternoon reference so same-day evening targets are still in the future. */
    private val nowAfternoon: LocalDateTime = LocalDateTime.of(2026, 7, 18, 15, 0, 0)

    private fun targetLocalTime(millis: Long): LocalTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toLocalTime()

    private fun targetDateTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun sleepEntry(
        dateOfSleep: String,
        startTime: String,
        isMainSleep: Boolean = true
    ): SleepLogEntry = SleepLogEntry(
        dateOfSleep = dateOfSleep,
        minutesAsleep = 420,
        isMainSleep = isMainSleep,
        timeInBed = 450,
        startTime = startTime,
        endTime = startTime // unused by resolver
    )

    @Test
    fun fallAsleepInClampWindow_23_00_clampsTo10pm() {
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-18T23:00:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(22, 0), targetLocalTime(millis))
        assertEquals(18, targetDateTime(millis).dayOfMonth)
    }

    @Test
    fun fallAsleepAtWindowStart_22_00_clampsTo10pm() {
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-18T22:00:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(22, 0), targetLocalTime(millis))
    }

    @Test
    fun fallAsleepAtWindowEnd_23_30_is10pm() {
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-18T23:30:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(22, 0), targetLocalTime(millis))
    }

    @Test
    fun fallAsleepAfterWindow_00_30_is11pm() {
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-19T00:30:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(23, 0), targetLocalTime(millis))
    }

    @Test
    fun fallAsleepBeforeWindow_21_00_is730pm_notClamped() {
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-18T21:00:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(19, 30), targetLocalTime(millis))
    }

    @Test
    fun oddMinutes_roundToNearestFive() {
        // 00:37 - 90m = 23:07 previous calendar day on the LocalDateTime; round 7 → 5 → 23:05
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-19T00:37:00",
            nowAfternoon
        )!!
        assertEquals(LocalTime.of(23, 5), targetLocalTime(millis))
    }

    @Test
    fun emptyLogs_fallbackToNext10pm() {
        val millis = BedtimeNotificationManager.resolveBedtimeMillis(emptyList(), nowAfternoon)
        assertEquals(LocalTime.of(22, 0), targetLocalTime(millis))
        assertEquals(18, targetDateTime(millis).dayOfMonth)
    }

    @Test
    fun napsOnly_fallbackToNext10pm() {
        val logs = listOf(
            sleepEntry("2026-07-18", "2026-07-18T14:00:00", isMainSleep = false)
        )
        val millis = BedtimeNotificationManager.resolveBedtimeMillis(logs, nowAfternoon)
        assertEquals(LocalTime.of(22, 0), targetLocalTime(millis))
    }

    @Test
    fun resolveUsesLatestMainSleep() {
        val logs = listOf(
            sleepEntry("2026-07-16", "2026-07-16T23:00:00"), // would clamp to 22:00
            sleepEntry("2026-07-18", "2026-07-19T00:30:00") // latest → 23:00
        )
        val millis = BedtimeNotificationManager.resolveBedtimeMillis(logs, nowAfternoon)
        assertEquals(LocalTime.of(23, 0), targetLocalTime(millis))
    }

    @Test
    fun targetAlreadyPassedToday_rollsToTomorrow() {
        val nowEvening = LocalDateTime.of(2026, 7, 18, 22, 30, 0)
        val millis = BedtimeNotificationManager.defaultBedtimeMillis(nowEvening)
        val target = targetDateTime(millis)
        assertEquals(LocalTime.of(22, 0), target.toLocalTime())
        assertEquals(19, target.dayOfMonth)
        assertTrue(target.isAfter(nowEvening))
    }

    @Test
    fun calculateTargetAlreadyPassed_rollsToTomorrow() {
        val nowLate = LocalDateTime.of(2026, 7, 18, 23, 45, 0)
        val millis = BedtimeNotificationManager.calculateBedtimeMillis(
            "2026-07-19T00:30:00", // → 23:00
            nowLate
        )!!
        val target = targetDateTime(millis)
        assertEquals(LocalTime.of(23, 0), target.toLocalTime())
        assertEquals(19, target.dayOfMonth)
    }
}
