package com.example.a24_hr_clock.logic

import kotlin.math.exp
import kotlin.math.max

object ExerciseMetricsCalculator {

    fun calculateTrimp(hrSeries: List<Int>, rhr: Double, maxHr: Double): Double {
        var trimpTotal = 0.0
        val k = 1.92 // standard for males

        for (hr in hrSeries) {
            if (hr > rhr) {
                val w = (hr - rhr) / (maxHr - rhr)
                trimpTotal += w * exp(k * w)
            }
        }
        return Math.round(trimpTotal * 100.0) / 100.0
    }

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

    fun calculateHrss(trimpScore: Double, weeklyAvgTrimp: Double): Double {
        if (weeklyAvgTrimp == 0.0) return 0.0
        return Math.round((trimpScore / weeklyAvgTrimp) * 100.0 * 10.0) / 10.0
    }

    fun generateSystemAlert(currentHrv: Double, hrssLoad: Double, medicatedBase: Double): String {
        return when {
            currentHrv < (medicatedBase - 5) -> "STATUS: DEPLETED. HRV is below medicated baseline. Strict rest recommended."
            hrssLoad > 120 && currentHrv <= medicatedBase -> "STATUS: OVERREACHING. High load detected with suppressed HRV. Focus on Zone 1 recovery."
            currentHrv > (medicatedBase + 5) -> "STATUS: OPTIMIZED. Nervous system is resilient today. Cleared for high intensity."
            else -> "STATUS: STEADY. System is holding at pharmacological baseline. Proceed as planned."
        }
    }
}
