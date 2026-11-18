package com.example.a24_hr_clock.logic

import java.time.LocalDate
import java.util.Locale

object RecommendationEngine {

    fun generateRecommendation(
        date: LocalDate,
        hrv7DayAvg: Double?,
        hrvBaseline: Double,
        rhr7DayAvg: Double?,
        rhrBaseline: Double,
        sleep7DayAvg: Double?,
        sleepNeed: Double,
        recentLoad: Double, // Relative load average
        trainingGoal: WeeklyGoals,
        checkIn: CheckIn?,
        dataQuality: Map<String, Boolean> // e.g., "hrv_stale", "hr_missing"
    ): Recommendation {
        val reasons = mutableListOf<String>()
        var confidence = Confidence.HIGH

        // 1. Check for red flags (Illness, Pain)
        val symptoms = checkIn?.symptoms
        if (symptoms != null && symptoms.lowercase().contains("ill")) {
            return Recommendation(
                date = date.toString(),
                state = RecommendationState.RECOVER,
                confidence = Confidence.HIGH,
                headline = "Focus on recovery",
                suggestions = "Illness detected. Prioritize rest, hydration, and sleep. Skip all training today.",
                reasons = listOf("Illness reported in check-in"),
                safetyText = "Consult a medical professional if symptoms persist."
            )
        }

        val pain = checkIn?.pain
        if (pain != null && pain > 3) {
            return Recommendation(
                date = date.toString(),
                state = RecommendationState.RECOVER,
                confidence = Confidence.HIGH,
                headline = "Manage pain",
                suggestions = "Pain level is elevated. Avoid activities that aggravate the area. Consider gentle mobility if appropriate.",
                reasons = listOf("High pain level reported"),
                alternatives = listOf("Gentle mobility", "Rest")
            )
        }

        // 2. Data Quality Check
        if (hrv7DayAvg == null || sleep7DayAvg == null || rhr7DayAvg == null) {
            return Recommendation(
                date = date.toString(),
                state = RecommendationState.INSUFFICIENT_DATA,
                confidence = Confidence.LOW,
                headline = "Insufficient data",
                suggestions = "Wear your device overnight to collect recovery signals.",
                dataQualityExplanation = "Missing recent HRV, RHR or sleep data."
            )
        }

        if (dataQuality["hrv_stale"] == true) {
            confidence = Confidence.LOW
            reasons.add("HRV data is stale")
        }

        // 3. Signal Analysis
        val hrvRatio = hrv7DayAvg / hrvBaseline
        val rhrDiff = rhr7DayAvg - rhrBaseline
        val sleepDeficit = sleepNeed - sleep7DayAvg

        if (hrvRatio < 0.9) {
            reasons.add("HRV trend is below your usual range")
        } else if (hrvRatio > 1.1) {
            reasons.add("HRV trend is elevated")
        }

        if (rhrDiff > 3) {
            reasons.add("Resting HR is ${rhrDiff.toInt()} bpm above baseline")
        }

        if (sleepDeficit > 1.0) {
            reasons.add("Sleep is ${String.format(Locale.US, "%.1f", sleepDeficit)}h below need")
        }

        // 4. Recommendation Logic (Conservative)
        return when {
            hrvRatio < 0.85 || (hrvRatio < 0.95 && rhrDiff > 5) || sleepDeficit > 2.0 -> {
                Recommendation(
                    date = date.toString(),
                    state = RecommendationState.RECOVER,
                    confidence = confidence,
                    headline = "Recovery signals are below usual range",
                    suggestions = "Consider reducing intensity today. 20-30 minutes of light activity or full rest.",
                    reasons = reasons,
                    alternatives = listOf("Mobility", "Walk", "Full rest"),
                    weeklyGoalContribution = "Recovery focus"
                )
            }
            hrvRatio > 1.05 && sleepDeficit < 0.5 && recentLoad < 110 -> {
                Recommendation(
                    date = date.toString(),
                    state = RecommendationState.BUILD,
                    confidence = confidence,
                    headline = "Your signals are in a favorable range",
                    suggestions = "A harder session may be reasonable if it aligns with your plan and you feel well.",
                    reasons = reasons,
                    intensity = "Moderate to High (Talk test: Difficult to speak)",
                    alternatives = listOf("Follow plan", "Cross-train"),
                    weeklyGoalContribution = trainingGoal.mode
                )
            }
            else -> {
                Recommendation(
                    date = date.toString(),
                    state = RecommendationState.MAINTAIN,
                    confidence = confidence,
                    headline = "Your signals are near your usual range",
                    suggestions = "Maintain your current training plan. Focus on consistency.",
                    reasons = reasons,
                    intensity = "Moderate (Talk test: Can speak but not sing)",
                    alternatives = listOf("Follow plan", "Active recovery")
                )
            }
        }
    }
}
