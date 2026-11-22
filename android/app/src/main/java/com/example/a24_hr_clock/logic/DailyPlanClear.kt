package com.example.a24_hr_clock.logic

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Stored state of the "clear all tasks daily" toggle.
 * [armedAtMillis] is when the clear was last switched on (or first seen after the update);
 * a 6:00 that passed before it is never caught up, so turning it on never wipes today's plan.
 * [lastClearDate] is the local date (ISO) of the last 6:00 that cleared, so it runs once per day.
 */
data class DailyClearState(
    val enabled: Boolean = true,
    val armedAtMillis: Long? = null,
    val lastClearDate: String? = null
)

object DailyPlanClear {
    val CLEAR_TIME: LocalTime = LocalTime.of(6, 0)

    enum class Decision { NONE, ARM, CLEAR }

    /** The 6:00 of [now]'s day if it has passed, else yesterday's. */
    fun latestDue(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(CLEAR_TIME).atZone(now.zone)
        return if (today.isAfter(now)) now.toLocalDate().minusDays(1).atTime(CLEAR_TIME).atZone(now.zone) else today
    }

    /** The first 6:00 strictly after [now]. */
    fun nextTrigger(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(CLEAR_TIME).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(CLEAR_TIME).atZone(now.zone)
    }

    fun decide(state: DailyClearState, now: ZonedDateTime): Decision {
        if (!state.enabled) return Decision.NONE
        val armedAt = state.armedAtMillis ?: return Decision.ARM
        val due = latestDue(now)
        if (due.toInstant().toEpochMilli() <= armedAt) return Decision.NONE
        if (state.lastClearDate == due.toLocalDate().toString()) return Decision.NONE
        return Decision.CLEAR
    }
}
