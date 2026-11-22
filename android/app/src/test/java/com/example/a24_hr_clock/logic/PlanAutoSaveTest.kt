package com.example.a24_hr_clock.logic

import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanAutoSaveTest {
    private val none = NewTaskTimes()

    @Test fun oneTimeIsNotEnough() {
        assertNull(none.with(PlanField.START, 600).resolve())
        assertNull(none.with(PlanField.DURATION, 60).resolve())
        assertNull(none.resolve())
    }

    @Test fun startAndDurationDeriveEnd() {
        val times = none.with(PlanField.START, 23 * 60).with(PlanField.DURATION, 120)
        assertEquals(23 * 60 to 120, times.resolve())
    }

    @Test fun startAndEndDeriveDurationAcrossMidnight() {
        assertEquals(23 * 60 to 120, none.with(PlanField.START, 23 * 60).with(PlanField.END, 60).resolve())
        assertEquals(600 to 1440, none.with(PlanField.END, 600).with(PlanField.START, 600).resolve())
    }

    @Test fun endAndDurationDeriveStart() {
        assertEquals(9 * 60 to 60, none.with(PlanField.END, 10 * 60).with(PlanField.DURATION, 60).resolve())
        assertEquals(23 * 60 to 120, none.with(PlanField.DURATION, 120).with(PlanField.END, 60).resolve())
    }

    @Test fun allThreeSetKeepsTheTwoMostRecent() {
        val times = none.with(PlanField.START, 600).with(PlanField.DURATION, 60).with(PlanField.END, 12 * 60)
        assertEquals(660 to 60, times.resolve()) // duration and end win; start recomputed to 11:00
        // Setting the first one again makes it the newest.
        assertEquals(480 to 240, times.with(PlanField.START, 480).resolve()) // end 12:00 and start 8:00 are now the newest two
    }

    @Test fun nameIsRequiredAndTrimmed() {
        val times = none.with(PlanField.START, 600).with(PlanField.DURATION, 30)
        assertNull(NewTaskTimes.taskFor("", times))
        assertNull(NewTaskTimes.taskFor("   ", times))
        assertNull(NewTaskTimes.taskFor("Read", none.with(PlanField.START, 600)))
        val task = NewTaskTimes.taskFor("  Read ", times)!!
        assertEquals("Read", task.name)
        assertEquals(600, task.startMinute)
        assertEquals(30, task.durationMinutes)
        assertEquals(true, task.isValid)
    }

    @Test fun editingATimeAgainReplacesIt() {
        val times = none.with(PlanField.START, 600).with(PlanField.START, 700).with(PlanField.DURATION, 20)
        assertEquals(700 to 20, times.resolve())
    }

    @Test fun capitalizesOnlyWhileTheTaskFieldIsEmpty() {
        assertEquals(KeyboardCapitalization.Sentences, taskNameCapitalization(""))
        assertEquals(KeyboardCapitalization.None, taskNameCapitalization("r"))
        assertNotEquals(KeyboardCapitalization.Sentences, taskNameCapitalization("read. "))
    }
}
