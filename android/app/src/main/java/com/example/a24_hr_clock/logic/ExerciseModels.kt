package com.example.a24_hr_clock.logic

import kotlinx.serialization.Serializable

@Serializable
enum class RecommendationState {
    RECOVER, MAINTAIN, BUILD, INSUFFICIENT_DATA
}

@Serializable
enum class Confidence {
    LOW, MEDIUM, HIGH
}

@Serializable
data class Recommendation(
    val date: String,
    val state: RecommendationState,
    val confidence: Confidence,
    val headline: String,
    val suggestions: String,
    val alternatives: List<String> = emptyList(),
    val durationRange: String? = null,
    val intensity: String? = null,
    val reasons: List<String> = emptyList(),
    val dataQualityExplanation: String? = null,
    val safetyText: String? = null,
    val weeklyGoalContribution: String? = null
)

@Serializable
data class CheckIn(
    val date: String,
    val symptoms: String? = null,
    val pain: Int? = null, // 0-10 or simple toggle
    val soreness: Int? = null,
    val fatigue: Int? = null,
    val stress: Int? = null,
    val readiness: Int? = null
)

@Serializable
data class SessionFeedback(
    val date: String,
    val sessionIndex: Int = 0,
    val rpe: Int? = null, // Rate of Perceived Exertion 1-10
    val difficulty: String? = null, // "easier", "as_planned", "harder"
    val note: String? = null
)

@Serializable
data class ExerciseSession(
    val id: String,
    val type: String,
    val date: String,
    val startTime: String,
    val durationMinutes: Int,
    val averageHeartRate: Int? = null,
    val calories: Int? = null,
    val load: Double? = null, // TRIMP or similar
    val source: String = "Fitbit",
    val distanceKm: Double? = null,
    val feedback: SessionFeedback? = null
)

@Serializable
data class WeeklyGoals(
    val moderateMinutesGoal: Int = 150,
    val vigorousMinutesGoal: Int = 75,
    val strengthDaysGoal: Int = 2,
    val loadTarget: Double? = null,
    val mode: String = "General Health" // "General health", "Maintain fitness", "Build endurance", "Strength emphasis", "Custom"
)

@Serializable
data class ExerciseScreenState(
    val recommendation: Recommendation? = null,
    val recentSessions: List<ExerciseSession> = emptyList(),
    val weeklyGoals: WeeklyGoals = WeeklyGoals(),
    val weeklyProgressModerate: Int = 0,
    val weeklyProgressVigorous: Int = 0,
    val weeklyProgressStrength: Int = 0,
    val lastSyncTime: Long? = null,
    val isSyncing: Boolean = false,
    val error: String? = null
)
