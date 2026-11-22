package com.example.a24_hr_clock.logic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One awake stretch inside a sleep log, in seconds from the log's start. Short keys keep the cache small. */
@Serializable
data class AwakePeriod(@SerialName("o") val offsetSec: Int, @SerialName("d") val durationSec: Int)

/** A red marker on the sleep wedge's rim, in canvas degrees (18:00 = 0, clockwise). */
data class AwakeArc(val startDeg: Float, val sweepDeg: Float)

object AwakePeriods {
    /** Wakes closer together than this read as one period. */
    const val MERGE_GAP_SEC = 60
    /** A 1-minute wake is a quarter degree; draw at least this much so it shows. */
    const val MIN_SWEEP_DEG = 2.0f

    /** Clamp raw (startSec, endSec) intervals to the log, sort them and merge near neighbours. */
    fun normalize(raw: List<Pair<Long, Long>>, logDurationSec: Long): List<AwakePeriod> {
        val merged = mutableListOf<LongArray>()
        for ((s, e) in raw.map { (s, e) -> s.coerceIn(0, logDurationSec) to e.coerceIn(0, logDurationSec) }
            .filter { it.second > it.first }.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && s - last[1] < MERGE_GAP_SEC) last[1] = maxOf(last[1], e) else merged += longArrayOf(s, e)
        }
        return merged.map { AwakePeriod(it[0].toInt(), (it[1] - it[0]).toInt()) }
    }

    /**
     * Arcs for one log. [startHour] is the wedge's clock start, [wedgeSweepDeg] its sweep; arcs stay inside it.
     * Short periods grow to [minSweepDeg] around their midpoint; arcs that then overlap are joined.
     */
    fun arcs(startHour: Double, wedgeSweepDeg: Double, periods: List<AwakePeriod>, minSweepDeg: Float = MIN_SWEEP_DEG): List<AwakeArc> {
        val wedgeStart = (startHour - 18.0) * 15.0
        val spans = periods.sortedBy { it.offsetSec }.map { p ->
            var a = p.offsetSec / 240.0          // 15 deg per hour = 1 deg per 240 s
            var b = (p.offsetSec + p.durationSec) / 240.0
            if (b - a < minSweepDeg) {
                val mid = (a + b) / 2
                a = mid - minSweepDeg / 2.0
                b = mid + minSweepDeg / 2.0
                if (a < 0) { b -= a; a = 0.0 }
                if (b > wedgeSweepDeg) { a -= b - wedgeSweepDeg; b = wedgeSweepDeg }
            }
            doubleArrayOf(a.coerceIn(0.0, wedgeSweepDeg), b.coerceIn(0.0, wedgeSweepDeg))
        }.filter { it[1] > it[0] }
        val joined = mutableListOf<DoubleArray>()
        for (span in spans) {
            val last = joined.lastOrNull()
            if (last != null && span[0] <= last[1]) last[1] = maxOf(last[1], span[1]) else joined += span
        }
        return joined.map { AwakeArc((wedgeStart + it[0]).toFloat(), (it[1] - it[0]).toFloat()) }
    }

    /** Stage names that mean awake. Not RESTLESS: classic logs count that as asleep. */
    fun isAwake(stage: String) = stage.equals("AWAKE", true) || stage.equals("WAKE", true)

    /** Keep periods only on the newest [nights] dates; older logs never reach the dial. */
    fun trim(logs: List<SleepLogEntry>, nights: Int = 3): List<SleepLogEntry> {
        val keep = logs.map { it.dateOfSleep }.distinct().sortedDescending().take(nights).toSet()
        return logs.map { if (it.dateOfSleep in keep || it.awakePeriods == null) it else it.copy(awakePeriods = null) }
    }
}
