package com.example.a24_hr_clock.logic

/**
 * Start, end and duration of a plan task. Start and duration are stored; end is derived,
 * so an end past midnight wraps and an end at or before the start means the next day.
 * Durations are 1..1440 minutes, so an end equal to the start is a full 24 hours.
 */
object PlanTime {
    const val DAY = 1440
    const val DIAL_MAX = 12 * 60

    fun endMinute(start: Int, duration: Int): Int = Math.floorMod(start + duration, DAY)

    /** The duration from [start] to the next [end]; equal times mean 24 hours. */
    fun durationTo(start: Int, end: Int): Int = Math.floorMod(end - start, DAY).let { if (it == 0) DAY else it }

    /** Parses a typed duration as "H:MM" or whole hours "H"; 0:01 to 24:00, else null. */
    fun parseDuration(text: String): Int? {
        val value = text.trim()
        val minutes = Regex("^(\\d{1,2})(?::(\\d{2}))?$").matchEntire(value)?.let { m ->
            val hours = m.groupValues[1].toInt()
            val mins = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (mins > 59) null else hours * 60 + mins
        }
        return minutes?.takeIf { it in 1..DAY }
    }

    fun formatDuration(minutes: Int): String = "%d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
}

val PlannedTask.endMinute: Int get() = PlanTime.endMinute(startMinute, durationMinutes)

/** Edit End: the duration follows, the start stays. */
fun PlannedTask.withEnd(end: Int): PlannedTask = copy(durationMinutes = PlanTime.durationTo(startMinute, end))

/** Edit Start (or drag the wedge): the duration stays, so the end moves with it. */
fun PlannedTask.withStart(start: Int): PlannedTask = copy(startMinute = Math.floorMod(start, PlanTime.DAY))

/** Edit Duration: the end follows, the start stays. */
fun PlannedTask.withDuration(duration: Int): PlannedTask = copy(durationMinutes = duration)
