package com.example.a24_hr_clock.logic

/**
 * The Plan table's order and how moving a row rewrites that task's times.
 *
 * The table always lists tasks by start time, earliest first; tasks that start together keep
 * the order they were saved in. Moving a row never reorders storage directly: it rewrites the
 * moved task's start (its duration is kept) so the task sits against its new neighbour, and
 * the list then orders itself by start time again.
 */
object PlanOrder {
    /** Tasks by start time, earliest first. Stable, so equal starts keep their saved order. */
    fun sorted(tasks: List<PlannedTask>): List<PlannedTask> = tasks.sortedBy { it.startMinute }

    /**
     * Move task [id] so it lands at [toIndex] of the table (0 = top), counted as in [sorted].
     *
     * - Moved up: the task ends when the task now directly below it begins.
     * - Moved down: the task starts when the task now directly above it ends.
     * - The duration never changes, and no other task changes. Times wrap past midnight.
     * - Overlaps with other tasks are allowed, as they are everywhere else in the plan.
     * - The result keeps [tasks]' own order; callers sort for display. If the new start sorts the
     *   task somewhere other than [toIndex] (a long task wrapping past midnight, or a start earlier
     *   than the tasks above it), the table simply shows it where its start puts it.
     *
     * Returns [tasks] unchanged when the id is unknown or the row did not move.
     */
    fun move(tasks: List<PlannedTask>, id: String, toIndex: Int): List<PlannedTask> {
        val table = sorted(tasks)
        val from = table.indexOfFirst { it.id == id }
        if (from < 0) return tasks
        val to = toIndex.coerceIn(0, table.lastIndex)
        if (to == from) return tasks
        val task = table[from]
        val arranged = table.toMutableList().apply { removeAt(from); add(to, task) }
        val moved = if (to < from) task.withStart(arranged[to + 1].startMinute - task.durationMinutes)
            else task.withStart(arranged[to - 1].endMinute)
        return tasks.map { if (it.id == id) moved else it }
    }
}
