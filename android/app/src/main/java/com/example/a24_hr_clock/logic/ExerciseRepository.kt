package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.exerciseDataStore by preferencesDataStore(name = "exercise_data")

class ExerciseRepository(
    private val context: Context,
    private val dataSource: ExerciseDataSource
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val DAILY_METRICS = stringPreferencesKey("daily_metrics")
    private val SESSIONS = stringPreferencesKey("sessions")
    private val CHECK_INS = stringPreferencesKey("check_ins")
    private val WEEKLY_GOALS = stringPreferencesKey("weekly_goals")

    val dailyMetricsFlow: Flow<List<DailyExerciseMetrics>> = context.exerciseDataStore.data.map { prefs ->
        val jsonStr = prefs[DAILY_METRICS] ?: "[]"
        try { json.decodeFromString<List<DailyExerciseMetrics>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    val sessionsFlow: Flow<List<ExerciseSession>> = context.exerciseDataStore.data.map { prefs ->
        val jsonStr = prefs[SESSIONS] ?: "[]"
        try { json.decodeFromString<List<ExerciseSession>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    val checkInsFlow: Flow<List<CheckIn>> = context.exerciseDataStore.data.map { prefs ->
        val jsonStr = prefs[CHECK_INS] ?: "[]"
        try { json.decodeFromString<List<CheckIn>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    val weeklyGoalsFlow: Flow<WeeklyGoals> = context.exerciseDataStore.data.map { prefs ->
        val jsonStr = prefs[WEEKLY_GOALS] ?: ""
        try { if (jsonStr.isEmpty()) WeeklyGoals() else json.decodeFromString<WeeklyGoals>(jsonStr) } catch (e: Exception) { WeeklyGoals() }
    }

    suspend fun saveWeeklyGoals(goals: WeeklyGoals) {
        context.exerciseDataStore.edit { it[WEEKLY_GOALS] = json.encodeToString(goals) }
    }

    suspend fun saveCheckIn(checkIn: CheckIn) {
        context.exerciseDataStore.edit { prefs ->
            val current = getCheckIns().toMutableList()
            current.removeAll { it.date == checkIn.date }
            current.add(checkIn)
            prefs[CHECK_INS] = json.encodeToString(current)
        }
    }

    suspend fun getCheckIns(): List<CheckIn> {
        val prefs = context.exerciseDataStore.data.first()
        val jsonStr = prefs[CHECK_INS] ?: "[]"
        return try { json.decodeFromString<List<CheckIn>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    suspend fun syncExerciseData(startDate: LocalDate, endDate: LocalDate) {
        val today = LocalDate.now()
        val safeEndDate = if (endDate.isAfter(today)) today else endDate
        if (startDate.isAfter(safeEndDate)) return

        val newMetrics = dataSource.fetchDailyMetrics(startDate.toString(), safeEndDate.toString())
        val newSessions = dataSource.fetchSessions(startDate.toString(), safeEndDate.toString())

        context.exerciseDataStore.edit { prefs ->
            // Merge metrics
            val currentMetrics = (prefs[DAILY_METRICS]?.let { try { json.decodeFromString<List<DailyExerciseMetrics>>(it) } catch (e: Exception) { null } } ?: emptyList()).toMutableList()
            for (m in newMetrics) {
                currentMetrics.removeAll { it.date == m.date }
                currentMetrics.add(m)
            }
            
            // Recalculate relative load (hrss) for all metrics using a 28-day rolling window
            val sorted = currentMetrics.sortedBy { it.date }
            val finalized = sorted.mapIndexed { index, metric ->
                val prev28 = sorted.subList(0, index).takeLast(28).filter { it.dataStatus == "valid" && it.trimp > 0 }
                val baseline = if (prev28.isNotEmpty()) prev28.map { it.trimp }.average() else 0.0
                metric.copy(hrss = ExerciseMetricsCalculator.calculateRelativeLoad(metric.trimp, baseline))
            }
            
            prefs[DAILY_METRICS] = json.encodeToString(finalized)

            // Merge sessions
            val currentSessions = (prefs[SESSIONS]?.let { try { json.decodeFromString<List<ExerciseSession>>(it) } catch (e: Exception) { null } } ?: emptyList()).toMutableList()
            for (s in newSessions) {
                currentSessions.removeAll { it.id == s.id }
                currentSessions.add(s)
            }
            prefs[SESSIONS] = json.encodeToString(currentSessions.sortedByDescending { it.date })
        }
    }
}
