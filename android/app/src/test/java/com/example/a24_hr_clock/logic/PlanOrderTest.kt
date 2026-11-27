package com.example.a24_hr_clock.logic

import org.junit.Assert.*
import org.junit.Test

class PlanOrderTest {
    private fun t(id: String, start: Int, dur: Int = 60) = PlannedTask(id, id, start, dur, 0)
    private fun h(hours: Int, minutes: Int = 0) = hours * 60 + minutes
    private fun List<PlannedTask>.byId(id: String) = first { it.id == id }
    private fun List<PlannedTask>.tableIds() = PlanOrder.sorted(this).map { it.id }

    // a 08:00-09:00, b 10:00-11:00, c 13:00-15:00 (2 h), d 16:00-16:30
    private val plan = listOf(t("c", h(13), 120), t("a", h(8)), t("d", h(16), 30), t("b", h(10)))

    @Test fun tableIsOrderedByStartTime() {
        assertEquals(listOf("a", "b", "c", "d"), plan.tableIds())
    }

    @Test fun equalStartsKeepSavedOrder() {
        val tied = listOf(t("x", h(9)), t("y", h(9)), t("w", h(7)))
        assertEquals(listOf("w", "x", "y"), tied.tableIds())
    }

    @Test fun movingUpEndsWhenTheTaskBelowBegins() {
        // c (2 h) above b: c ends at b's start, 10:00, so it starts 08:00.
        val result = PlanOrder.move(plan, "c", 1)
        assertEquals(h(8), result.byId("c").startMinute)
        assertEquals(h(10), result.byId("c").endMinute)
        assertEquals(120, result.byId("c").durationMinutes)
    }

    @Test fun movingDownStartsWhenTheTaskAboveEnds() {
        // a (1 h) below b: a starts at b's end, 11:00.
        val result = PlanOrder.move(plan, "a", 1)
        assertEquals(h(11), result.byId("a").startMinute)
        assertEquals(h(12), result.byId("a").endMinute)
    }

    @Test fun onlyTheMovedTaskChanges() {
        val result = PlanOrder.move(plan, "c", 1)
        for (id in listOf("a", "b", "d")) assertEquals(plan.byId(id), result.byId(id))
        assertEquals(plan.map { it.id }, result.map { it.id })
    }

    @Test fun movedToTheTopEndsWhenTheOldFirstTaskBegins() {
        // b to the top: ends at a's start 08:00, so starts 07:00.
        val result = PlanOrder.move(plan, "b", 0)
        assertEquals(h(7), result.byId("b").startMinute)
        assertEquals("b", result.tableIds().first())
    }

    @Test fun movedToTheBottomStartsWhenTheOldLastTaskEnds() {
        // a to the bottom: starts at d's end 16:30.
        val result = PlanOrder.move(plan, "a", 3)
        assertEquals(h(16, 30), result.byId("a").startMinute)
        assertEquals("a", result.tableIds().last())
    }

    @Test fun movingSeveralRowsUsesTheNeighbourItLandsNextTo() {
        // d up two rows, above b: ends at b's start 10:00, 30 min, so starts 09:30.
        val result = PlanOrder.move(plan, "d", 1)
        assertEquals(h(9, 30), result.byId("d").startMinute)
        // a down two rows, below c: starts at c's end 15:00.
        assertEquals(h(15), PlanOrder.move(plan, "a", 2).byId("a").startMinute)
    }

    @Test fun movingUpWrapsBeforeMidnight() {
        val early = listOf(t("a", h(0, 30)), t("b", h(2)), t("c", h(5)))
        // b to the top, 1 h: ends 00:30, starts 23:30 the day before. The table sorts it last.
        val result = PlanOrder.move(early, "b", 0)
        assertEquals(h(23, 30), result.byId("b").startMinute)
        assertEquals(h(0, 30), result.byId("b").endMinute)
        assertEquals(60, result.byId("b").durationMinutes)
    }

    @Test fun movingDownWrapsPastMidnight() {
        val late = listOf(t("a", h(1)), t("b", h(20)), t("c", h(23), 120))
        // a below c: c ends 01:00 next day, so a starts 01:00.
        assertEquals(h(1), PlanOrder.move(late, "a", 2).byId("a").startMinute)
        // b below c as well: also 01:00.
        assertEquals(h(1), PlanOrder.move(late, "b", 2).byId("b").startMinute)
    }

    @Test fun taskCrossingMidnightAsNeighbourUsesItsWrappedEnd() {
        val plan2 = listOf(t("a", h(6)), t("n", h(22), 180)) // n 22:00 - 01:00
        val result = PlanOrder.move(plan2, "a", 1)
        assertEquals(h(1), result.byId("a").startMinute)
    }

    @Test fun fullDayTaskKeepsItsDuration() {
        val plan3 = listOf(t("a", h(6), 1440), t("b", h(9)))
        val result = PlanOrder.move(plan3, "a", 1)
        assertEquals(1440, result.byId("a").durationMinutes)
        assertEquals(h(10), result.byId("a").startMinute)
    }

    @Test fun overlapsAreAllowed() {
        // c (2 h) above a 08:00 task that has a task before it: new times overlap the earlier task.
        val p = listOf(t("x", h(8)), t("y", h(9)), t("z", h(9, 30), 180))
        val result = PlanOrder.move(p, "z", 1) // z ends at y's start 09:00 -> 06:00-09:00 overlaps x
        assertEquals(h(6), result.byId("z").startMinute)
        assertEquals(h(9), result.byId("z").endMinute)
    }

    @Test fun movingToTiedNeighbourUsesItsStart() {
        val tied = listOf(t("a", h(9)), t("b", h(9)), t("c", h(12)))
        // c above b (index 1: a, c, b): c ends at b's start 09:00.
        assertEquals(h(8), PlanOrder.move(tied, "c", 1).byId("c").startMinute)
    }

    @Test fun noOpMovesChangeNothing() {
        assertSame(plan, PlanOrder.move(plan, "b", 1)) // already row 1
        assertSame(plan, PlanOrder.move(plan, "nope", 0))
        assertEquals(plan, PlanOrder.move(plan, "a", 0))
    }

    @Test fun indexIsClamped() {
        assertEquals(PlanOrder.move(plan, "a", 3), PlanOrder.move(plan, "a", 99))
        assertEquals(PlanOrder.move(plan, "d", 0), PlanOrder.move(plan, "d", -5))
    }

    @Test fun emptyAndSingleTaskPlansDoNothing() {
        assertEquals(emptyList<PlannedTask>(), PlanOrder.move(emptyList(), "a", 0))
        val one = listOf(t("a", h(8)))
        assertSame(one, PlanOrder.move(one, "a", 0))
        assertSame(one, PlanOrder.move(one, "a", 5))
    }

    @Test fun aMoveIsOneUndoStep() {
        val history = PlanHistory()
        val after = PlanOrder.move(plan, "c", 1)
        history.record(plan, after)
        assertNotEquals(plan, after)
        assertEquals(plan, history.undo(after))
        assertFalse(history.canUndo)
        assertEquals(after, history.redo(plan))
    }

    @Test fun aNoOpMoveIsNotRecorded() {
        val history = PlanHistory()
        history.record(plan, PlanOrder.move(plan, "b", 1))
        assertFalse(history.canUndo)
    }
}
