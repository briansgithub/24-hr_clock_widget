package com.example.a24_hr_clock.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One stage stretch inside a sleep log, in seconds from the log's start. [level]: see [SleepStages]. */
@Serializable
data class StageSeg(
    @SerialName("o") val offsetSec: Int,
    @SerialName("d") val durationSec: Int,
    @SerialName("l") val level: Int
)

/** A point of the smoothed stage curve: [offsetSec] from the log start, [fraction] of the dial radius. */
data class CurvePoint(val offsetSec: Int, val fraction: Double)

object SleepStages {
    const val WAKE = 0
    const val REM = 1
    const val LIGHT = 2
    const val DEEP = 3

    /** Wakes shorter than this are folded into the stage before them so the curve stays smooth. */
    const val MIN_WAKE_SEC = 180
    const val SAMPLE_SEC = 60
    /** Half-width of the triangular smoothing window. */
    const val SMOOTH_HALF_SEC = 360

    /** Distance from the dial centre as a fraction of the radius; wake meets the sleep wedge's rim. */
    fun radiusFraction(level: Int): Double = when (level) {
        WAKE -> 0.85
        REM -> 0.65
        LIGHT -> 0.50
        else -> 0.30
    }

    /** Fitbit stages/classic and Google Health names; null for anything unknown. */
    fun levelOf(name: String): Int? = when (name.lowercase()) {
        "wake", "awake" -> WAKE
        "rem" -> REM
        "light", "restless", "asleep", "sleep" -> LIGHT
        "deep" -> DEEP
        else -> null
    }

    /** Clamp raw (startSec, endSec, level) to the log, sort, fold short wakes into the previous stage, merge equal neighbours. */
    fun normalize(raw: List<Triple<Long, Long, Int>>, logDurationSec: Long): List<StageSeg> {
        val clean = raw.map { (s, e, l) -> Triple(s.coerceIn(0, logDurationSec), e.coerceIn(0, logDurationSec), l) }
            .filter { it.second > it.first }.sortedBy { it.first }
        if (clean.isEmpty()) return emptyList()
        val folded = clean.mapIndexed { i, t ->
            if (t.third == WAKE && t.second - t.first < MIN_WAKE_SEC) {
                val other = clean.getOrNull(i - 1) ?: clean.getOrNull(i + 1)
                if (other != null && other !== t && !(other.third == WAKE && other.second - other.first < MIN_WAKE_SEC)) t.copy(third = other.third) else t
            } else t
        }
        val out = mutableListOf<LongArray>()
        for ((s, e, l) in folded) {
            val last = out.lastOrNull()
            if (last != null && last[2] == l.toLong() && s - last[1] <= 1) last[1] = maxOf(last[1], e) else out += longArrayOf(s, e, l.toLong())
        }
        return out.map { StageSeg(it[0].toInt(), (it[1] - it[0]).toInt(), it[2].toInt()) }
    }

    private fun fractionAt(segs: List<StageSeg>, t: Int): Double {
        val seg = segs.lastOrNull { it.offsetSec <= t } ?: segs.first()
        return radiusFraction(seg.level)
    }

    /**
     * Smoothed curve over the log: a triangular moving average of the stage radii, so it never leaves
     * the range of the real levels and joins up at stage changes. Empty when there are no stages.
     */
    fun curve(segs: List<StageSeg>, durationSec: Int): List<CurvePoint> {
        if (segs.isEmpty() || durationSec <= 0) return emptyList()
        val step = 30
        val grid = (0..durationSec step step).map { fractionAt(segs, it) }
        val half = SMOOTH_HALF_SEC / step
        val points = mutableListOf<CurvePoint>()
        var t = 0
        while (true) {
            val c = (t / step).coerceAtMost(grid.size - 1)
            var sum = 0.0
            var wsum = 0.0
            for (k in -half..half) {
                val idx = (c + k).coerceIn(0, grid.size - 1)
                val w = (half + 1 - kotlin.math.abs(k)).toDouble()
                sum += grid[idx] * w
                wsum += w
            }
            points += CurvePoint(t, sum / wsum)
            if (t >= durationSec) break
            t = minOf(t + SAMPLE_SEC, durationSec)
        }
        return points
    }
}
