package com.example.a24_hr_clock.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.a24_hr_clock.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Base64

class FitbitManager(private val context: Context) : ExerciseDataSource {
    private val session = FitbitSession.get(context)
    val store = session.store
    private val http = session.http
    private val json = Json { ignoreUnknownKeys = true }
    val sleepLogsFlow = store.sleepLogsFlow
    val sleepSnapshotFlow = store.snapshotFlow
    val statusFlow = store.statusFlow
    val authFlow = store.credentialsFlow
    val metricsFlow = store.metricsFlow
    val loginStatusFlow = authFlow.map { it.accessToken.isNotBlank() }

    override suspend fun getSourceInfo() = "Fitbit Web API"
    suspend fun isLoggedIn() = store.credentials().accessToken.isNotBlank()
    suspend fun getLastSleepLogs() = store.snapshot().logs
    suspend fun saveMetrics(bathyphase: Double?, efficiency: Double, sleepNeed: Double) = store.saveMetrics(bathyphase, efficiency, sleepNeed)

    suspend fun getAuthUrl(): String {
        fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val attempt = OAuthAttempt(random(), random(), System.currentTimeMillis())
        store.saveOAuth(attempt)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse("https://www.fitbit.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.FITBIT_CLIENT_ID)
            .appendQueryParameter("response_type", "code").appendQueryParameter("scope", "sleep heartrate activity")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("state", attempt.state)
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256").build().toString()
    }

    suspend fun handleAuthCallback(uri: Uri): Boolean {
        if (uri.scheme != "fitbit24h" || uri.host != "callback" || !uri.path.isNullOrEmpty() || uri.port != -1) return false
        val attempt = store.consumeOAuth() ?: run { Log.w("FitbitAuth", "callback rejected: no pending attempt"); return false }
        val code = OAuthCallback.code(uri.toString(), attempt.state, attempt.createdAt, System.currentTimeMillis()) ?: run {
            Log.w("FitbitAuth", "callback rejected: stateValid=${uri.getQueryParameters("state") == listOf(attempt.state)} fragmentPresent=${uri.fragment != null} denied=${uri.getQueryParameter("error") != null} ageValid=${System.currentTimeMillis() - attempt.createdAt in 0..600_000}")
            return false
        }
        return try { http.exchangeCode(code, attempt.verifier, REDIRECT); Log.i("FitbitAuth", "authorization completed"); true }
        catch (e: CancellationException) { throw e }
        catch (e: FitbitRequestException) { Log.w("FitbitAuth", "authorization failed: ${e.category}"); false }
    }

    suspend fun logout() {
        PushRegistrationManager.get(context).unregister()
        http.clearCredentials()
        store.clearAfterLogout()
    }

    suspend fun fetchSleepLogs(startDate: String, endDate: String): SleepFetchResult = try {
        SleepResponseParser.parse(http.request("/1.2/user/-/sleep/date/$startDate/$endDate.json").body)
    } catch (e: FitbitRequestException) { SleepFetchResult.Failed(e.category, e.retryAt) }

    suspend fun fetchHeartRateIntraday(startTimeIso: String, endTimeIso: String): List<HeartRatePointEntry> {
        val start = LocalDateTime.parse(startTimeIso.removeSuffix("Z"))
        val end = LocalDateTime.parse(endTimeIso.removeSuffix("Z"))
        val points = mutableListOf<HeartRatePointEntry>()
        var date = start.toLocalDate()
        while (date <= end.toLocalDate()) {
            val body = http.request("/1/user/-/activities/heart/date/$date/1d/1min.json").body
            val day = json.decodeFromString<HeartRateResponse>(body).activities_heart_intraday?.dataset.orEmpty()
            points += day.filter { LocalDateTime.of(date, LocalTime.parse(it.time)).let { time -> time >= start && time <= end } }
            date = date.plusDays(1)
        }
        return points
    }

    suspend fun fetchHRV(dateStr: String): Double? = json.decodeFromString<HRVResponse>(http.request("/1/user/-/hrv/date/$dateStr.json").body).hrv.firstOrNull()?.value?.dailyRmssd

    override suspend fun fetchDailyMetrics(startDate: String, endDate: String): List<DailyExerciseMetrics> {
        val settings = SettingsManager(context).modelSettingsFlow.first()
        val results = mutableListOf<DailyExerciseMetrics>()
        var date = LocalDate.parse(startDate)
        val end = minOf(LocalDate.parse(endDate), LocalDate.now())
        while (date <= end) {
            val hr = json.decodeFromString<HeartRateResponse>(http.request("/1/user/-/activities/heart/date/$date/1d/1min.json").body)
            val points = hr.activities_heart_intraday?.dataset.orEmpty()
            val rhr = hr.activities_heart?.firstOrNull()?.value?.restingHeartRate?.toDouble()
            val hrv = fetchHRV(date.toString())
            results += DailyExerciseMetrics(date.toString(),
                ExerciseMetricsCalculator.calculateTrimp(points.map { it.value }, rhr ?: settings.restingHR, settings.maxHR, settings.isMale),
                hrv, rhr, hrMissing = points.isEmpty(), hrvMissing = hrv == null,
                dataStatus = if (points.isEmpty() && hrv == null) "missing" else "valid")
            date = date.plusDays(1)
        }
        return results
    }
    override suspend fun fetchSessions(startDate: String, endDate: String): List<ExerciseSession> = emptyList()

    /** Maintenance reads the published sleep cache; it cannot change its freshness. */
    suspend fun refreshMetrics() {
        val settings = SettingsManager(context).modelSettingsFlow.first()
        val mainLogs = getLastSleepLogs().filter { it.isMainSleep }
        mainLogs.maxByOrNull { it.endTime }?.let { main ->
            val efficiency = mainLogs.map { if (it.timeInBed > 0) it.minutesAsleep.toDouble() / it.timeInBed else .92 }.average()
            val hr = fetchHeartRateIntraday(main.startTime, main.endTime)
            saveMetrics(EnergyCalculator.findBathyphase(hr.map { HeartRatePoint(it.time, it.value) }), efficiency, settings.bedtimeGoal * efficiency)
        }
        val today = LocalDate.now()
        // Reconcile older exercise history daily; repeated seven-day downloads compete with sleep's rate budget.
        val maintenance = context.getSharedPreferences("fitbit_maintenance", Context.MODE_PRIVATE)
        val fullHistory = maintenance.getString("history_day", "") != today.toString()
        ExerciseRepository(context, this).syncExerciseData(today.minusDays(if (fullHistory) 6 else 1), today)
        if (fullHistory) maintenance.edit().putString("history_day", today.toString()).apply()
    }
    companion object { const val REDIRECT = "fitbit24h://callback" }
}
