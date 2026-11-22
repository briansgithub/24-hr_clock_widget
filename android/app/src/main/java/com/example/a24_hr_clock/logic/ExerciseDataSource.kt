package com.example.a24_hr_clock.logic

interface ExerciseDataSource {
    suspend fun fetchDailyMetrics(startDate: String, endDate: String): List<DailyExerciseMetrics>
    suspend fun fetchSessions(startDate: String, endDate: String): List<ExerciseSession>
    suspend fun getSourceInfo(): String
}
