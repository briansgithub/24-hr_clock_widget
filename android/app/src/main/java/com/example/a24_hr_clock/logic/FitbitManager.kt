package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*

private val Context.dataStore by preferencesDataStore(name = "fitbit_prefs")

class FitbitManager(private val context: Context) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val clientId = "YOUR_FITBIT_CLIENT_ID" // From Python script
    private val clientSecret = "YOUR_FITBIT_CLIENT_SECRET" // From Python script
    private val redirectUri = "fitbit24h://callback" // Simple scheme for Fitbit dashboard

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    private val LAST_SLEEP_LOGS = stringPreferencesKey("last_sleep_logs")
    private val BATHYPHASE = androidx.datastore.preferences.core.doublePreferencesKey("bathyphase")
    private val EFFICIENCY = androidx.datastore.preferences.core.doublePreferencesKey("efficiency")
    private val SLEEP_NEED = androidx.datastore.preferences.core.doublePreferencesKey("sleep_need")
    private val EXERCISE_METRICS = stringPreferencesKey("exercise_metrics")

    suspend fun saveMetrics(bathyphase: Double?, efficiency: Double, sleepNeed: Double) {
        context.dataStore.edit { prefs ->
            if (bathyphase != null) prefs[BATHYPHASE] = bathyphase
            prefs[EFFICIENCY] = efficiency
            prefs[SLEEP_NEED] = sleepNeed
        }
    }

    val metricsFlow = context.dataStore.data.map { data ->
        Triple(
            data[BATHYPHASE],
            data[EFFICIENCY] ?: 1.0,
            data[SLEEP_NEED] ?: 9.75
        )
    }

    val loginStatusFlow = context.dataStore.data.map { it[ACCESS_TOKEN] != null }

    val exerciseMetricsFlow: Flow<List<DailyExerciseMetrics>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[EXERCISE_METRICS] ?: "[]"
        try { json.decodeFromString<List<DailyExerciseMetrics>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    suspend fun getExerciseMetrics(): List<DailyExerciseMetrics> {
        val prefs = context.dataStore.data.first()
        val jsonStr = prefs[EXERCISE_METRICS] ?: "[]"
        return try { json.decodeFromString<List<DailyExerciseMetrics>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    suspend fun saveExerciseMetrics(metrics: List<DailyExerciseMetrics>) {
        context.dataStore.edit { prefs ->
            prefs[EXERCISE_METRICS] = json.encodeToString(metrics)
        }
    }

    suspend fun getMetrics(): Triple<Double?, Double, Double> {
        val data = context.dataStore.data.first()
        return Triple(
            data[BATHYPHASE],
            data[EFFICIENCY] ?: 1.0,
            data[SLEEP_NEED] ?: 9.75
        )
    }

    fun getAuthUrl(): String {
        val scope = "sleep heartrate"
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "https://www.fitbit.com/oauth2/authorize?client_id=$clientId&response_type=code&scope=$scope&redirect_uri=$encodedRedirect&expires_in=604800"
    }

    suspend fun handleAuthCode(code: String): Boolean = withContext(Dispatchers.IO) {
        Log.d("FitbitManager", "Handling auth code: $code")
        val authHeader = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .build()

        val request = Request.Builder()
            .url("https://api.fitbit.com/oauth2/token")
            .post(formBody)
            .addHeader("Authorization", "Basic $authHeader")
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        try {
            val response = client.newCall(request).execute()
            Log.d("FitbitManager", "Token response code: ${response.code}")
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext false
                val tokens = json.decodeFromString<FitbitTokens>(body)
                saveTokens(tokens)
                Log.d("FitbitManager", "Tokens saved successfully")
                return@withContext true
            } else {
                Log.e("FitbitManager", "Token exchange failed: ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e("FitbitManager", "Error exchanging code", e)
        }
        return@withContext false
    }

    suspend fun isLoggedIn(): Boolean {
        return getAccessToken() != null
    }

    suspend fun logout() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun saveTokens(tokens: FitbitTokens) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = tokens.access_token
            prefs[REFRESH_TOKEN] = tokens.refresh_token
        }
    }

    val sleepLogsFlow = context.dataStore.data.map { prefs ->
        val jsonString = prefs[LAST_SLEEP_LOGS] ?: return@map emptyList<SleepLogEntry>()
        try {
            json.decodeFromString<List<SleepLogEntry>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getLastSleepLogs(): List<SleepLogEntry> {
        val jsonString = context.dataStore.data.map { it[LAST_SLEEP_LOGS] }.first() ?: return emptyList()
        return try {
            json.decodeFromString<List<SleepLogEntry>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveSleepLogs(logs: List<SleepLogEntry>) {
        val jsonString = json.encodeToString(logs)
        context.dataStore.edit { prefs ->
            prefs[LAST_SLEEP_LOGS] = jsonString
        }
    }

    private suspend fun getAccessToken(): String? {
        return context.dataStore.data.map { it[ACCESS_TOKEN] }.first()
    }

    private suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = context.dataStore.data.map { it[REFRESH_TOKEN] }.first() ?: return@withContext false
        val authHeader = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://api.fitbit.com/oauth2/token")
            .post(formBody)
            .addHeader("Authorization", "Basic $authHeader")
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext false
            val tokens = json.decodeFromString<FitbitTokens>(body)
            saveTokens(tokens)
            return@withContext true
        }
        return@withContext false
    }

    suspend fun fetchSleepLogs(startDate: String, endDate: String): List<SleepLogEntry> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        val url = "https://api.fitbit.com/1.2/user/-/sleep/date/$startDate/$endDate.json"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()

        var response = client.newCall(request).execute()
        if (response.code == 401) {
            if (refreshTokens()) {
                val newToken = getAccessToken()
                val newRequest = request.newBuilder().header("Authorization", "Bearer $newToken").build()
                response = client.newCall(newRequest).execute()
            }
        }

        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext emptyList()
            val logs = json.decodeFromString<SleepResponse>(body).sleep
            saveSleepLogs(logs)
            return@withContext logs
        }
        return@withContext emptyList()
    }

    suspend fun fetchHeartRateIntraday(startTimeIso: String, endTimeIso: String): List<HeartRatePointEntry> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        
        val startDt = try { java.time.LocalDateTime.parse(startTimeIso.replace("Z", "")) } catch (e: Exception) { return@withContext emptyList() }
        val endDt = try { java.time.LocalDateTime.parse(endTimeIso.replace("Z", "")) } catch (e: Exception) { return@withContext emptyList() }
        
        val datesNeeded = mutableListOf<String>()
        var cursor = startDt.toLocalDate()
        while (!cursor.isAfter(endDt.toLocalDate())) {
            datesNeeded.add(cursor.toString())
            cursor = cursor.plusDays(1)
        }

        val allPoints = mutableListOf<HeartRatePointEntry>()
        
        for (dateStr in datesNeeded) {
            val url = "https://api.fitbit.com/1/user/-/activities/heart/date/$dateStr/1d/1min.json"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()

            var response = client.newCall(request).execute()
            if (response.code == 401) {
                if (refreshTokens()) {
                    val newToken = getAccessToken()
                    val newRequest = request.newBuilder().header("Authorization", "Bearer $newToken").build()
                    response = client.newCall(newRequest).execute()
                }
            }

            if (response.isSuccessful) {
                val body = response.body?.string() ?: continue
                val points = try {
                    json.decodeFromString<HeartRateResponse>(body).activities_heart_intraday?.dataset
                } catch (e: Exception) { null } ?: continue
                
                for (p in points) {
                    val ptTime = java.time.LocalTime.parse(p.time)
                    val ptDt = java.time.LocalDateTime.of(java.time.LocalDate.parse(dateStr), ptTime)
                    if (!ptDt.isBefore(startDt) && !ptDt.isAfter(endDt)) {
                        allPoints.add(p)
                    }
                }
            }
        }
        return@withContext allPoints
    }

    suspend fun fetchHRV(dateStr: String): Double? = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext null
        val url = "https://api.fitbit.com/1/user/-/hrv/date/$dateStr.json"
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
        var response = client.newCall(request).execute()
        if (response.code == 401 && refreshTokens()) {
            val newToken = getAccessToken()
            response = client.newCall(request.newBuilder().header("Authorization", "Bearer $newToken").build()).execute()
        }
        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext null
            try { json.decodeFromString<HRVResponse>(body).hrv.firstOrNull()?.value?.dailyRmssd } catch (e: Exception) { null }
        } else null
    }

    suspend fun fetchExerciseMetricsForRange(startDateStr: String, endDateStr: String): List<DailyExerciseMetrics> = withContext(Dispatchers.IO) {
        val settingsManager = SettingsManager(context)
        val modelSettings = settingsManager.modelSettingsFlow.first()
        val todayStr = java.time.LocalDate.now().toString()
        
        val start = java.time.LocalDate.parse(startDateStr)
        val end = java.time.LocalDate.parse(endDateStr)
        
        val existing = getExerciseMetrics().toMutableList()
        var modified = false
        
        var cursor = start
        while (!cursor.isAfter(end)) {
            val dateStr = cursor.toString()
            val isPastDay = cursor.isBefore(java.time.LocalDate.now())
            val cached = existing.find { it.date == dateStr }
            
            if (isPastDay && cached != null) {
                // Already have this past day
            } else {
                Log.d("FitbitManager", "Fetching exercise data for $dateStr")
                val hrIntraday = fetchHeartRateIntraday(dateStr + "T00:00:00", dateStr + "T23:59:59")
                val hrv = fetchHRV(dateStr) ?: modelSettings.hrvMedicatedBase
                val trimp = ExerciseMetricsCalculator.calculateTrimp(hrIntraday.map { it.value }, modelSettings.restingHR, 220.0 - modelSettings.userAge)
                val newMetric = DailyExerciseMetrics(dateStr, trimp, hrv)
                
                if (cached != null) {
                    existing[existing.indexOf(cached)] = newMetric
                } else {
                    existing.add(newMetric)
                }
                modified = true
            }
            cursor = cursor.plusDays(1)
        }
        
        if (modified) {
            val avgTrimp = if (existing.isNotEmpty()) existing.map { it.trimp }.average() else 0.0
            val finalized = existing.map { 
                it.copy(hrss = ExerciseMetricsCalculator.calculateHrss(it.trimp, avgTrimp))
            }.sortedBy { it.date }
            
            saveExerciseMetrics(finalized)
            return@withContext finalized.filter { it.date >= startDateStr && it.date <= endDateStr }
        }
        
        return@withContext existing.filter { it.date >= startDateStr && it.date <= endDateStr }.sortedBy { it.date }
    }

    suspend fun refreshMetrics(force: Boolean = false) {
        val settingsManager = SettingsManager(context)
        val modelSettings = settingsManager.modelSettingsFlow.first()
        
        val now = java.time.LocalDate.now()
        val logs = fetchSleepLogs(now.minusDays(14).toString(), now.toString())
        
        // Efficiency & Bathyphase (Existing Logic)
        if (logs.isNotEmpty()) {
            val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep }
            if (mainSleep != null) {
                val avgEff = logs.filter { it.timeInBed > 0 }
                    .map { it.minutesAsleep.toDouble() / it.timeInBed }
                    .average()
                val eff = if (avgEff.isNaN()) 0.92 else avgEff
                
                val hrPoints = fetchHeartRateIntraday(mainSleep.startTime, mainSleep.endTime)
                val bathy = EnergyCalculator.findBathyphase(hrPoints.map { HeartRatePoint(it.time, it.value) })
                
                bathy?.let { b ->
                    saveMetrics(b, eff, modelSettings.bedtimeGoal * eff)
                }
            }
        }

        // Smart Sync Exercise Metrics for the last 7 days
        fetchExerciseMetricsForRange(now.minusDays(6).toString(), now.toString())
    }
}
