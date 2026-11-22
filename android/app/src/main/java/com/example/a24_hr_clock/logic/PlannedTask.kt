package com.example.a24_hr_clock.logic

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class PlannedTask(
    val id: String,
    val name: String,
    val startMinute: Int,
    val durationMinutes: Int,
    val color: Int
) {
    val isValid: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && startMinute in 0..1439 && durationMinutes in 1..1440

    fun containsMinute(minute: Double): Boolean =
        ((minute - startMinute + 1440.0) % 1440.0) < durationMinutes

    companion object {
        private val colors = listOf(
            0xFF63C5DA, 0xFFFFB86B, 0xFFB99AFF, 0xFF7CD6A2,
            0xFFFF8EAA, 0xFFFFD76B, 0xFF86ABFF, 0xFFD6A3DA
        )

        fun create(name: String, startMinute: Int, durationMinutes: Int): PlannedTask =
            PlannedTask(UUID.randomUUID().toString(), name.trim(), startMinute, durationMinutes,
                colors[Random.nextInt(colors.size)].toInt())

        fun parseStartTime(value: String): Int? {
            val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value.trim()) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
        }

        fun formatStartTime(minute: Int): String =
            "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
    }
}

/** The same geometry drives drawing and hit testing in every clock size. */
data class ClockDial(val centerX: Float, val centerY: Float, val radius: Float) {
    fun minuteAt(x: Float, y: Float): Double? {
        // The angle is unstable right at the fixed pivot.
        if (hypot(x - centerX, y - centerY) < max(8f, radius * 0.08f)) return null
        val degrees = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble()))
        return ((degrees / 15.0 + 18.0) * 60.0 + 1440.0) % 1440.0
    }

    fun taskAt(x: Float, y: Float, tasks: List<PlannedTask>): PlannedTask? {
        if (radius < 20f || hypot(x - centerX, y - centerY) > radius) return null
        val minute = minuteAt(x, y) ?: return null
        // Last drawn wins where tasks overlap.
        return tasks.lastOrNull { it.isValid && it.containsMinute(minute) }
    }

    fun rotatedStart(task: PlannedTask, initialPointerMinute: Double, x: Float, y: Float): Int {
        val minute = minuteAt(x, y) ?: return task.startMinute
        return Math.floorMod(task.startMinute + (minute - initialPointerMinute).roundToInt(), 1440)
    }

    companion object {
        fun create(width: Int, height: Int, smallTopRight: Boolean, topInsetPx: Float,
                   sideInsetPx: Float, dialMarginPx: Float, centered: Boolean = false): ClockDial {
            val radius = if (smallTopRight) min(width, height) / 6f * 0.9f
                else min(width, height) / 2f - dialMarginPx
            val centerX = if (smallTopRight) width - radius - sideInsetPx else width / 2f
            val centerY = when {
                centered -> height / 2f
                smallTopRight -> radius + topInsetPx
                else -> height / 2f - 1.75f * max(12f, radius / 7f)
            }
            return ClockDial(centerX, centerY, radius)
        }
    }
}
