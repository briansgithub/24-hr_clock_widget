package com.example.a24_hr_clock.logic

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarWindowTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0, day: Int = 18) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun event(title: String, from: Long, to: Long, allDay: Boolean = false) = CalendarRawEvent(title, from, to, allDay, 0)

    private val events = listOf(
        event("morning", at(10), at(11)),
        event("evening", at(19), at(20)),
        event("holiday", at(0), at(0, day = 19), allDay = true)
    )

    @Test fun anEventThatHasEndedLeavesTheDial() {
        // Before the fix the wallpaper kept the 9:00 list all day, so at 12:00 "morning" was still drawn.
        assertEquals(listOf("morning", "evening"), CalendarWindow.build(events, at(9), zone).map { it.title })
        assertEquals(listOf("evening"), CalendarWindow.build(events, at(12), zone).map { it.title })
    }

    @Test fun aCurrentEventIsDrawnFromTheClockHand() {
        val now = at(10, 30)
        val current = CalendarWindow.build(events, now, zone).single { it.title == "morning" }
        assertTrue(current.isCurrent)
        assertEquals(10.5, current.startHour, 1e-9)
        assertEquals(11.0, current.endHour, 1e-9)
    }

    @Test fun tomorrowsEventsAppearOnceTheyAreWithin24Hours() {
        val tomorrow = listOf(event("tomorrow", at(9, day = 19), at(10, day = 19)))
        assertTrue(CalendarWindow.build(tomorrow, at(8), zone).isEmpty())
        assertEquals(listOf("tomorrow"), CalendarWindow.build(tomorrow, at(9, 30), zone).map { it.title })
    }

    @Test fun allDayEventsAreNotDrawnAsArcs() {
        assertTrue(CalendarWindow.build(events, at(9), zone).none { it.title == "holiday" })
    }

    private fun cal(id: Long, primary: Boolean = false, visible: Boolean = true) = CalendarInfo(id, "c$id", "a", primary, 0, visible)

    @Test fun savedCalendarIdsFromAnotherPhoneFallBackToPrimaries() {
        assertEquals(setOf(7L), CalendarSelection.reconcile(setOf(1L, 2L), listOf(cal(7, primary = true), cal(8))))
    }

    @Test fun savedCalendarIdsThatStillExistAreKept() {
        assertEquals(setOf(8L), CalendarSelection.reconcile(setOf(8L, 99L), listOf(cal(7, primary = true), cal(8))))
    }

    @Test fun aChoiceOfOnlyHiddenCalendarsFallsBackToPrimaries() {
        // The Pixel 9 Pro had only a hidden, unsynced calendar selected, so Personal never showed.
        val phone = listOf(cal(7, primary = true), cal(15, visible = false))
        assertEquals(setOf(7L), CalendarSelection.reconcile(setOf(15L), phone))
        assertEquals(setOf(7L, 15L), CalendarSelection.reconcile(setOf(7L, 15L), phone))
    }

    @Test fun choosingNoCalendarsIsRespected() {
        assertEquals(emptySet<Long>(), CalendarSelection.reconcile(emptySet(), listOf(cal(7, primary = true))))
    }
}
