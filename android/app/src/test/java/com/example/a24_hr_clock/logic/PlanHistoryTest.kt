package com.example.a24_hr_clock.logic

import org.junit.Assert.*
import org.junit.Test

class PlanHistoryTest {
    private fun task(id: String, start: Int) = PlannedTask(id, id, start, 60, 0)
    private val empty = emptyList<PlannedTask>()
    private val a = listOf(task("a", 0))
    private val moved = listOf(task("a", 120))
    private val two = listOf(task("a", 120), task("b", 300))

    @Test fun emptyHistoryHasNothingToStepTo() {
        val h = PlanHistory()
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo(a)); assertNull(h.redo(a))
    }

    @Test fun undoAndRedoStepInOrder() {
        val h = PlanHistory()
        h.record(empty, a); h.record(a, moved); h.record(moved, two)
        assertEquals(moved, h.undo(two))
        assertEquals(a, h.undo(moved))
        assertEquals(empty, h.undo(a))
        assertFalse(h.canUndo)
        assertEquals(a, h.redo(empty))
        assertEquals(moved, h.redo(a))
        assertEquals(two, h.redo(moved))
        assertFalse(h.canRedo)
        assertTrue(h.canUndo)
    }

    @Test fun newEditAfterUndoClearsRedo() {
        val h = PlanHistory()
        h.record(empty, a); h.record(a, moved)
        h.undo(moved)
        assertTrue(h.canRedo)
        h.record(a, two)
        assertFalse(h.canRedo)
        assertNull(h.redo(two))
    }

    @Test fun oneRecordIsOneStepSoADragUndoesInOneTap() {
        val h = PlanHistory()
        h.record(a, moved) // drag end: single record
        assertEquals(a, h.undo(moved))
        assertFalse(h.canUndo)
    }

    @Test fun unchangedEditIsNotRecorded() {
        val h = PlanHistory()
        h.record(a, a)
        assertFalse(h.canUndo)
        h.record(a, moved); h.undo(moved)
        h.record(a, a)
        assertTrue(h.canRedo)
    }

    @Test fun oldestStepsDropAtTheLimit() {
        val h = PlanHistory(limit = 2)
        h.record(empty, a); h.record(a, moved); h.record(moved, two)
        assertEquals(moved, h.undo(two))
        assertEquals(a, h.undo(moved))
        assertNull(h.undo(a))
    }
}
