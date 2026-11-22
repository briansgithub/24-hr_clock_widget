package com.example.a24_hr_clock.logic

import com.example.a24_hr_clock.logic.DailyPlanClear.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyPlanClearTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int = 0, zone: ZoneId = ny) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)
    private fun millis(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun nextTriggerIsTodayBeforeSixAndTomorrowFromSix() {
        assertEquals(at(19, 6), DailyPlanClear.nextTrigger(at(19, 5, 59)))
        assertEquals(at(20, 6), DailyPlanClear.nextTrigger(at(19, 6)))
        assertEquals(at(20, 6), DailyPlanClear.nextTrigger(at(19, 23, 30)))
    }

    @Test fun nextTriggerCrossesMonthEnd() {
        assertEquals(ZonedDateTime.of(2026, 12, 1, 6, 0, 0, 0, ny), DailyPlanClear.nextTrigger(at(30, 7)))
    }

    @Test fun latestDueIsYesterdayBeforeSix() {
        assertEquals(at(18, 6), DailyPlanClear.latestDue(at(19, 5)))
        assertEquals(at(19, 6), DailyPlanClear.latestDue(at(19, 6)))
    }

    @Test fun firstRunArmsWithoutClearing() {
        // Installing the update at 9:00 must not wipe today's tasks.
        assertEquals(Decision.ARM, DailyPlanClear.decide(DailyClearState(), at(19, 9)))
    }

    @Test fun sixThatPassedBeforeArmingIsNotCaughtUp() {
        val state = DailyClearState(armedAtMillis = millis(at(19, 9)))
        assertEquals(Decision.NONE, DailyPlanClear.decide(state, at(19, 9, 1)))
        assertEquals(Decision.NONE, DailyPlanClear.decide(state, at(20, 5, 59)))
        assertEquals(Decision.CLEAR, DailyPlanClear.decide(state, at(20, 6)))
    }

    @Test fun turningOnBeforeSixClearsThatMorning() {
        val state = DailyClearState(armedAtMillis = millis(at(19, 5)))
        assertEquals(Decision.CLEAR, DailyPlanClear.decide(state, at(19, 6, 1)))
    }

    @Test fun missedSixIsCaughtUpLater() {
        // Phone off at 6:00; the app or wallpaper wakes at 14:00 the same day.
        val state = DailyClearState(armedAtMillis = millis(at(18, 22)))
        assertEquals(Decision.CLEAR, DailyPlanClear.decide(state, at(19, 14)))
    }

    @Test fun clearsAtMostOncePerDay() {
        val state = DailyClearState(armedAtMillis = millis(at(18, 22)), lastClearDate = "2026-11-19")
        assertEquals(Decision.NONE, DailyPlanClear.decide(state, at(19, 6, 5)))
        assertEquals(Decision.NONE, DailyPlanClear.decide(state, at(20, 5)))
        assertEquals(Decision.CLEAR, DailyPlanClear.decide(state, at(20, 6)))
    }

    @Test fun flyingWestDoesNotClearTwiceOnTheSameDate() {
        // Cleared at 6:00 New York, then 6:00 Los Angeles comes three hours later.
        val state = DailyClearState(armedAtMillis = millis(at(18, 22)), lastClearDate = "2026-11-19")
        assertEquals(Decision.NONE, DailyPlanClear.decide(state, at(19, 6, 30, ZoneId.of("America/Los_Angeles"))))
    }

    @Test fun toggleOffNeverClears() {
        val off = DailyClearState(enabled = false, armedAtMillis = millis(at(10, 0)))
        assertEquals(Decision.NONE, DailyPlanClear.decide(off, at(19, 6, 1)))
        assertEquals(Decision.NONE, DailyPlanClear.decide(DailyClearState(enabled = false), at(19, 7)))
    }
}
