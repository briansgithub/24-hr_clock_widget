package com.example.a24_hr_clock.logic

/**
 * In-memory undo/redo over whole-plan snapshots. Lives for the app session only.
 * Callers pass the plan as it is *before* an edit to [record]; one call is one step,
 * so a whole drag is recorded once, when it ends.
 */
class PlanHistory(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<List<PlannedTask>>()
    private val redoStack = ArrayDeque<List<PlannedTask>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Record an edit that turned [before] into [after]. Ignores edits that change nothing. */
    fun record(before: List<PlannedTask>, after: List<PlannedTask>) {
        if (before == after) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Returns the plan to restore, or null if there is nothing to undo. */
    fun undo(current: List<PlannedTask>): List<PlannedTask>? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return previous
    }

    /** Returns the plan to restore, or null if there is nothing to redo. */
    fun redo(current: List<PlannedTask>): List<PlannedTask>? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }
}
