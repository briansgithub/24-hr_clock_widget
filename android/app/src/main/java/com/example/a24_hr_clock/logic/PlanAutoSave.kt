package com.example.a24_hr_clock.logic

import androidx.compose.ui.text.input.KeyboardCapitalization
import java.io.Serializable

/**
 * When the Plan tab's new-task row saves by itself.
 *
 * The row is saved as soon as it has a name and any two of Start, End and Duration; the third is
 * derived from those two. If all three were set, the two most recently set ones win and the
 * third is recomputed. Nothing is saved on a keystroke: the Plan screen only asks [resolve] when a
 * time is picked, or when the name field loses focus or the keyboard sends Done.
 */
enum class PlanField(val code: Char) { START('S'), END('E'), DURATION('D') }

data class NewTaskTimes(
    val start: Int? = null,
    val end: Int? = null,
    val duration: Int? = null,
    /** Fields in the order they were set, oldest first, e.g. "SD". */
    val order: String = ""
) : Serializable {

    fun with(field: PlanField, minutes: Int): NewTaskTimes {
        val newOrder = order.replace(field.code.toString(), "") + field.code
        return when (field) {
            PlanField.START -> copy(start = minutes, order = newOrder)
            PlanField.END -> copy(end = minutes, order = newOrder)
            PlanField.DURATION -> copy(duration = minutes, order = newOrder)
        }
    }

    /**
     * The start and duration these times mean, or null while fewer than two are set. A
     * [defaultStart] (the end of the previous task) stands in as the oldest-set Start while the
     * user has not picked one, so one more time is enough; any two picked times override it.
     */
    fun resolve(defaultStart: Int? = null): Pair<Int, Int>? {
        val t = if (start == null && defaultStart != null) {
            copy(start = defaultStart, order = "S$order")
        } else this
        return t.resolveExplicit()
    }

    private fun resolveExplicit(): Pair<Int, Int>? {
        val set = order.filter { isSet(it) }
        if (set.length < 2) return null
        val pair = set.takeLast(2).toSet()
        return when (pair) {
            setOf('S', 'E') -> start!! to PlanTime.durationTo(start, end!!)
            setOf('S', 'D') -> start!! to duration!!
            else -> Math.floorMod(end!! - duration!!, PlanTime.DAY) to duration!!
        }
    }

    private fun isSet(code: Char) = when (code) {
        'S' -> start != null
        'E' -> end != null
        else -> duration != null
    }

    companion object {
        /** Where a new task starts by default: the end of the last task in the list, wrapping past midnight. */
        fun defaultStartAfter(tasks: List<PlannedTask>): Int? = tasks.lastOrNull()?.endMinute

        /** The task to add for [name] and [times], or null while the row is incomplete. */
        fun taskFor(name: String, times: NewTaskTimes, defaultStart: Int? = null): PlannedTask? {
            if (name.isBlank()) return null
            val (start, duration) = times.resolve(defaultStart) ?: return null
            return PlannedTask.create(name, start, duration)
        }
    }
}

/** First letter capitalizes only while the Task field is empty; once there is text, the user is in charge. */
fun taskNameCapitalization(text: String): KeyboardCapitalization =
    if (text.isEmpty()) KeyboardCapitalization.Sentences else KeyboardCapitalization.None
