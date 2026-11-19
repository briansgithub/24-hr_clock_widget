package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTimeTest {
    private val read = PlannedTask("r", "Read", 23 * 60, 120, 0)

    @Test fun endWrapsPastMidnight() {
        assertEquals(60, read.endMinute) // 23:00 + 2 h = 01:00
        assertEquals(0, PlanTime.endMinute(23 * 60, 60))
        assertEquals(10 * 60, PlanTime.endMinute(10 * 60, 1440))
    }

    @Test fun endBeforeStartMeansNextDay() {
        assertEquals(120, PlanTime.durationTo(23 * 60, 60))
        assertEquals(1439, PlanTime.durationTo(600, 599))
        assertEquals(1, PlanTime.durationTo(1439, 0))
    }

    @Test fun endEqualToStartIsAFullDay() {
        assertEquals(1440, PlanTime.durationTo(600, 600))
        assertTrue(read.withEnd(read.startMinute).isValid)
    }

    @Test fun editingEndChangesOnlyTheDuration() {
        val edited = read.withEnd(2 * 60 + 30)
        assertEquals(read.startMinute, edited.startMinute)
        assertEquals(210, edited.durationMinutes)
        assertEquals(150, edited.endMinute)
    }

    @Test fun editingDurationMovesTheEnd() {
        val edited = read.withDuration(30)
        assertEquals(read.startMinute, edited.startMinute)
        assertEquals(23 * 60 + 30, edited.endMinute)
    }

    @Test fun editingStartKeepsDurationAndMovesTheEnd() {
        val edited = read.withStart(22 * 60)
        assertEquals(120, edited.durationMinutes)
        assertEquals(0, edited.endMinute)
        assertEquals(1410, read.withStart(-30).startMinute) // wraps to 23:30
    }

    @Test fun typedDurationsAcceptOneMinuteToTwentyFourHours() {
        assertEquals(1, PlanTime.parseDuration("0:01"))
        assertEquals(90, PlanTime.parseDuration("1:30"))
        assertEquals(13 * 60 + 5, PlanTime.parseDuration(" 13:05 "))
        assertEquals(1440, PlanTime.parseDuration("24:00"))
        assertEquals(1440, PlanTime.parseDuration("24"))
        assertEquals(5 * 60, PlanTime.parseDuration("5"))
    }

    @Test fun typedDurationsRejectZeroOverADayAndJunk() {
        assertNull(PlanTime.parseDuration("0:00"))
        assertNull(PlanTime.parseDuration("0"))
        assertNull(PlanTime.parseDuration("24:01"))
        assertNull(PlanTime.parseDuration("25"))
        assertNull(PlanTime.parseDuration("1:60"))
        assertNull(PlanTime.parseDuration("1:5"))
        assertNull(PlanTime.parseDuration("-1:00"))
        assertNull(PlanTime.parseDuration(""))
        assertNull(PlanTime.parseDuration("abc"))
    }

    @Test fun formatsDurations() {
        assertEquals("0:05", PlanTime.formatDuration(5))
        assertEquals("24:00", PlanTime.formatDuration(1440))
    }

    /** Each edit is one undo step that brings back start, end and duration together. */
    @Test fun eachEditTypeUndoesAndRedoesAsOneStep() {
        val edits = listOf<(PlannedTask) -> PlannedTask>(
            { it.withEnd(3 * 60) },          // End
            { it.withDuration(45) },         // Duration
            { it.withStart(20 * 60) },       // Start
            { it.withStart(it.startMinute + 95) } // wedge drag, committed on release
        )
        for (edit in edits) {
            val history = PlanHistory()
            val before = listOf(read)
            val after = listOf(edit(read))
            history.record(before, after)
            val undone = history.undo(after)!!.single()
            assertEquals(read.startMinute, undone.startMinute)
            assertEquals(read.endMinute, undone.endMinute)
            assertEquals(read.durationMinutes, undone.durationMinutes)
            assertFalse(history.canUndo)
            assertEquals(after, history.redo(before))
        }
    }
}
