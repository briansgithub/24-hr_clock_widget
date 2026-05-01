package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
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

    private suspend fun saveTokens(tokens: FitbitTokens) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = tokens.access_token
            prefs[REFRESH_TOKEN] = tokens.refresh_token
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

    suspend fun fetchHeartRateIntraday(date: String, startTime: String, endTime: String): List<HeartRatePointEntry> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        // Fitbit intraday HR endpoint: /1/user/-/activities/heart/date/[date]/1d/1min/time/[startTime]/[endTime].json
        val url = "https://api.fitbit.com/1/user/-/activities/heart/date/$date/1d/1min/time/$startTime/$endTime.json"

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
            return@withContext json.decodeFromString<HeartRateResponse>(body).activities_heart_intraday?.dataset ?: emptyList()
        }
        return@withContext emptyList()
    }
}
