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
import com.example.a24_hr_clock.BuildConfig
import java.io.IOException
import java.util.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "fitbit_prefs")

class FitbitManager(private val context: Context) : ExerciseDataSource {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val clientId = BuildConfig.FITBIT_CLIENT_ID
    private val clientSecret = BuildConfig.FITBIT_CLIENT_SECRET
    private val redirectUri = "fitbit24h://callback"

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    private val LAST_SLEEP_LOGS = stringPreferencesKey("last_sleep_logs")
    private val BATHYPHASE = androidx.datastore.preferences.core.doublePreferencesKey("bathyphase")
    private val EFFICIENCY = androidx.datastore.preferences.core.doublePreferencesKey("efficiency")
    private val SLEEP_NEED = androidx.datastore.preferences.core.doublePreferencesKey("sleep_need")

    override suspend fun getSourceInfo(): String = "Fitbit Web API"

    override suspend fun fetchDailyMetrics(startDate: String, endDate: String): List<DailyExerciseMetrics> = withContext(Dispatchers.IO) {
        val settingsManager = SettingsManager(context)
        val modelSettings = settingsManager.modelSettingsFlow.first()
        val today = LocalDate.now()
        
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)

        val results = mutableListOf<DailyExerciseMetrics>()
        
        var cursor = start
        while (!cursor.isAfter(end) && !cursor.isAfter(today)) {
            val dateStr = cursor.toString()
            Log.d("FitbitManager", "Fetching daily metrics for $dateStr")
            
            val url = "https://api.fitbit.com/1/user/-/activities/heart/date/$dateStr/1d/1min.json"
            val token = getAccessToken() ?: return@withContext results
            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            
            var response = client.newCall(request).execute()
            if (response.code == 401 && refreshTokens()) {
                val newToken = getAccessToken()
                response = client.newCall(request.newBuilder().header("Authorization", "Bearer $newToken").build()).execute()
            }
            
            var hrIntraday = emptyList<HeartRatePointEntry>()
            var rhr: Double? = null
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val hrResponse = try { json.decodeFromString<HeartRateResponse>(body) } catch(e: Exception) { null }
                hrIntraday = hrResponse?.activities_heart_intraday?.dataset ?: emptyList()
                rhr = hrResponse?.activities_heart?.firstOrNull()?.value?.restingHeartRate?.toDouble()
            }

            val hrv = fetchHRV(dateStr)
            
            val trimp = ExerciseMetricsCalculator.calculateTrimp(
                hrSeries = hrIntraday.map { it.value },
                rhr = rhr ?: modelSettings.restingHR,
                maxHr = modelSettings.maxHR,
                isMale = modelSettings.isMale
            )
            
            results.add(DailyExerciseMetrics(
                date = dateStr,
                trimp = trimp,
                hrv = hrv,
                rhr = rhr,
                hrMissing = hrIntraday.isEmpty(),
                hrvMissing = hrv == null,
                dataStatus = if (hrIntraday.isEmpty() && hrv == null) "missing" else "valid"
            ))
            cursor = cursor.plusDays(1)
        }
        return@withContext results
    }

    override suspend fun fetchSessions(startDate: String, endDate: String): List<ExerciseSession> = withContext(Dispatchers.IO) {
        // Placeholder for future Activity API integration
        emptyList()
    }

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

    fun getAuthUrl(): String {
        val scope = "sleep heartrate activity"
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        return "https://www.fitbit.com/oauth2/authorize?client_id=$clientId&response_type=code&scope=$scope&redirect_uri=$encodedRedirect&expires_in=604800"
    }

    suspend fun handleAuthCode(code: String): Boolean = withContext(Dispatchers.IO) {
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
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext false
                val tokens = json.decodeFromString<FitbitTokens>(body)
                saveTokens(tokens)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("FitbitManager", "Error exchanging code", e)
        }
        return@withContext false
    }

    suspend fun isLoggedIn(): Boolean = getAccessToken() != null

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
        try { json.decodeFromString<List<SleepLogEntry>>(jsonString) } catch (e: Exception) { emptyList() }
    }

    suspend fun getLastSleepLogs(): List<SleepLogEntry> {
        val jsonString = context.dataStore.data.map { it[LAST_SLEEP_LOGS] }.first() ?: return emptyList()
        return try { json.decodeFromString<List<SleepLogEntry>>(jsonString) } catch (e: Exception) { emptyList() }
    }

    private suspend fun saveSleepLogs(logs: List<SleepLogEntry>) {
        val jsonString = json.encodeToString(logs)
        context.dataStore.edit { prefs -> prefs[LAST_SLEEP_LOGS] = jsonString }
    }

    private suspend fun getAccessToken(): String? = context.dataStore.data.map { it[ACCESS_TOKEN] }.first()

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
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()

        var response = client.newCall(request).execute()
        if (response.code == 401 && refreshTokens()) {
            val newToken = getAccessToken()
            response = client.newCall(request.newBuilder().header("Authorization", "Bearer $newToken").build()).execute()
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
        val startDt = try { LocalDateTime.parse(startTimeIso.replace("Z", "")) } catch (e: Exception) { return@withContext emptyList() }
        val endDt = try { LocalDateTime.parse(endTimeIso.replace("Z", "")) } catch (e: Exception) { return@withContext emptyList() }
        
        val datesNeeded = mutableListOf<String>()
        var cursor = startDt.toLocalDate()
        while (!cursor.isAfter(endDt.toLocalDate())) {
            datesNeeded.add(cursor.toString())
            cursor = cursor.plusDays(1)
        }

        val allPoints = mutableListOf<HeartRatePointEntry>()
        for (dateStr in datesNeeded) {
            val url = "https://api.fitbit.com/1/user/-/activities/heart/date/$dateStr/1d/1min.json"
            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()

            var response = client.newCall(request).execute()
            if (response.code == 401 && refreshTokens()) {
                val newToken = getAccessToken()
                response = client.newCall(request.newBuilder().header("Authorization", "Bearer $newToken").build()).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string() ?: continue
                val points = try { json.decodeFromString<HeartRateResponse>(body).activities_heart_intraday?.dataset } catch (e: Exception) { null } ?: continue
                for (p in points) {
                    val ptDt = LocalDateTime.of(LocalDate.parse(dateStr), LocalTime.parse(p.time))
                    if (!ptDt.isBefore(startDt) && !ptDt.isAfter(endDt)) allPoints.add(p)
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

    suspend fun refreshMetrics(force: Boolean = false) {
        val settingsManager = SettingsManager(context)
        val modelSettings = settingsManager.modelSettingsFlow.first()
        val now = LocalDate.now()
        val logs = fetchSleepLogs(now.minusDays(14).toString(), now.toString())
        
        if (logs.isNotEmpty()) {
            val mainSleep = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep }
            if (mainSleep != null) {
                val mainSleepLogs = logs.filter { it.isMainSleep }
                val avgEff = if (mainSleepLogs.isNotEmpty()) {
                    mainSleepLogs.map { if (it.timeInBed > 0) it.minutesAsleep.toDouble() / it.timeInBed else 0.92 }.average()
                } else 0.92
                val eff = if (avgEff.isNaN()) 0.92 else avgEff
                val hrPoints = fetchHeartRateIntraday(mainSleep.startTime, mainSleep.endTime)
                val bathy = EnergyCalculator.findBathyphase(hrPoints.map { HeartRatePoint(it.time, it.value) })
                bathy?.let { b -> saveMetrics(b, eff, modelSettings.bedtimeGoal * eff) }
            }
        }

        val exerciseRepository = ExerciseRepository(context, this)
        exerciseRepository.syncExerciseData(now.minusDays(6), now)
    }
}
