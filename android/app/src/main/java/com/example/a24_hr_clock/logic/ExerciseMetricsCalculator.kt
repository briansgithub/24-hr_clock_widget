package com.example.a24_hr_clock.logic

import kotlin.math.exp
import kotlin.math.max

object ExerciseMetricsCalculator {

    /**
     * Calculates Banister-style Training Impulse (TRIMP).
     * @param hrSeries Heart rate samples (1-minute intervals preferred).
     * @param rhr Resting Heart Rate.
     * @param maxHr Maximum Heart Rate.
     * @param isMale Whether to use the male (1.92) or female (1.67) coefficient.
     */
    fun calculateTrimp(hrSeries: List<Int>, rhr: Double, maxHr: Double, isMale: Boolean = true): Double {
        var trimpTotal = 0.0
        val k = if (isMale) 1.92 else 1.67

        for (hr in hrSeries) {
            if (hr > rhr && maxHr > rhr) {
                val w = (hr - rhr) / (maxHr - rhr)
                trimpTotal += w * exp(k * w)
            }
        }
        return Math.round(trimpTotal * 100.0) / 100.0
    }

    /**
     * Calculates relative load against a deterministic baseline.
     * @param trimpScore Current day's TRIMP.
     * @param baselineTrimp Reference TRIMP (e.g., 28-day rolling average of valid data).
     */
    fun calculateRelativeLoad(trimpScore: Double, baselineTrimp: Double): Double {
        if (baselineTrimp <= 0.0) return 0.0
        return Math.round((trimpScore / baselineTrimp) * 100.0 * 10.0) / 10.0
    }

    // Deprecated legacy name for compatibility during refactor
    fun calculateHrss(trimpScore: Double, weeklyAvgTrimp: Double): Double = calculateRelativeLoad(trimpScore, weeklyAvgTrimp)

    /**
     * Experimental EPOC calculation.
     */
    fun calculateEpoc(hrSeries: List<Int>, rhr: Double): Double {
        var epoc = 0.0
        val gain = 0.05
        val decay = 0.001

        for (hr in hrSeries) {
            val intensity = max(0.0, hr.toDouble() - rhr)
            val delta = (gain * intensity) - (decay * epoc)
            epoc += delta
        }
        return Math.round(epoc * 100.0) / 100.0
    }
}
