package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val Context.fitbitDataStore by preferencesDataStore(name = "fitbit_prefs")
data class SleepSnapshot(val logs: List<SleepLogEntry>, val status: SleepSyncStatus, val auth: FitbitCredentials)

class FitbitStore(context: Context) : FitbitCredentialStore {
    private val context = context.applicationContext
    private val data = this.context.fitbitDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val authKey = stringPreferencesKey("credentials_v2")
    private val logsKey = stringPreferencesKey("last_sleep_logs")
    private val statusKey = stringPreferencesKey("sleep_sync_status")
    private val retryKey = longPreferencesKey("fitbit_retry_at")
    private val oauthKey = stringPreferencesKey("oauth_pending")
    private val notifiedKey = stringPreferencesKey("notified_auth_episode")
    private val notifiedInstallKey = longPreferencesKey("notified_auth_install")
    private val consumedReconnectKey = stringPreferencesKey("consumed_reconnect_request")
    private val notificationMutex = Mutex()

    private inline fun <reified T> decode(value: String?, fallback: T): T =
        try { if (value == null) fallback else json.decodeFromString<T>(value) } catch (_: Exception) { fallback }
    private fun auth(p: Preferences): FitbitCredentials = decode(p[authKey], FitbitCredentials(
        accessToken = p[stringPreferencesKey("access_token")].orEmpty(), refreshToken = p[stringPreferencesKey("refresh_token")].orEmpty()))
    private fun status(p: Preferences) = decode(p[statusKey], SleepSyncStatus())
    private fun logs(p: Preferences) = decode(p[logsKey], emptyList<SleepLogEntry>())
    val credentialsFlow = data.data.map { auth(it) }
    val snapshotFlow = data.data.map { SleepSnapshot(logs(it), status(it), auth(it)) }
    val statusFlow = data.data.map { status(it) }
    val sleepLogsFlow = data.data.map { logs(it) }
    val metricsFlow = data.data.map { Triple(it[doublePreferencesKey("bathyphase")], it[doublePreferencesKey("efficiency")] ?: 1.0, it[doublePreferencesKey("sleep_need")] ?: 9.75) }

    override suspend fun credentials() = auth(data.data.first())
    override suspend fun saveCredentials(credentials: FitbitCredentials) {
        var saved = false
        data.edit {
            // A late legacy refresh/callback must never undo a verified provider switch.
            if (credentials.accessToken.isNotBlank() && credentials.provider != auth(it).provider) return@edit
            saved = true
            it[authKey] = json.encodeToString(credentials)
            if (credentials.provider == "fitbit") {
                it[stringPreferencesKey("access_token")] = credentials.accessToken
                it[stringPreferencesKey("refresh_token")] = credentials.refreshToken
            }
            if (credentials.reconnectEpisode == null) it.remove(notifiedKey)
        }
        if (saved && credentials.reconnectEpisode == null) AuthNotificationManager(context).cancel()
    }
    override suspend fun requireReconnect() = requireReconnectForProvider(credentials().provider)
    override suspend fun requireReconnectForProvider(provider: String) {
        data.edit {
            val current = auth(it)
            if (current.provider != provider) return@edit
            if (current.accessToken.isNotBlank() && current.reconnectEpisode == null) {
                it[authKey] = json.encodeToString(current.copy(reconnectEpisode = UUID.randomUUID().toString()))
            }
        }
        ensureAuthNotification()
    }
    suspend fun ensureAuthNotification() = notificationMutex.withLock {
        val p = data.data.first()
        val episode = auth(p).reconnectEpisode ?: return@withLock
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        // Android may remove notifications during an APK upgrade. Re-arm once for that installation.
        if ((p[notifiedKey] != episode || p[notifiedInstallKey] != installedAt) && AuthNotificationManager(context).showReauthNotification("$episode:$installedAt", if (auth(p).provider == "google_health") "Google Health" else "Fitbit")) {
            data.edit { it[notifiedKey] = episode; it[notifiedInstallKey] = installedAt }
        }
    }
    suspend fun consumeReconnectRequest(requestId: String): Boolean {
        var consumed = false
        data.edit { p ->
            if (p[consumedReconnectKey] != requestId) {
                p[consumedReconnectKey] = requestId
                consumed = true
            }
        }
        return consumed
    }
    suspend fun dismissReconnect() { data.edit { p -> p[authKey] = json.encodeToString(auth(p).let { it.copy(dismissedEpisode = it.reconnectEpisode) }) } }
    override suspend fun retryAt() = data.data.first()[retryKey] ?: 0L
    override suspend fun deferUntil(time: Long) { data.edit { it[retryKey] = maxOf(it[retryKey] ?: 0, time) } }
    suspend fun snapshot() = snapshotFlow.first()
    suspend fun beginAttempt(now: Long) { data.edit { it[statusKey] = json.encodeToString(status(it).copy(lastAttemptAt = now, phase = SleepSyncPhase.CHECKING)) } }
    suspend fun record(result: SleepFetchResult, start: String, end: String, now: Long, provider: String? = null): SleepSyncStatus {
        var updated = SleepSyncStatus()
        data.edit { p ->
            if (provider != null && auth(p).provider != provider) { updated = status(p); return@edit }
            val old = logs(p)
            val prior = status(p)
            val incoming = when (result) {
                is SleepFetchResult.Complete -> result.logs
                is SleepFetchResult.Pending -> result.partialLogs
                is SleepFetchResult.Failed -> null
            }
            val merged = incoming?.let { SleepCache.merge(old, it, start, end, result is SleepFetchResult.Complete) } ?: old
            updated = when (result) {
                is SleepFetchResult.Complete -> prior.copy(lastVerifiedAt = now, phase = SleepSyncPhase.CURRENT, error = null, nextRetryAt = 0, retryCount = 0, serverRetryAt = 0)
                is SleepFetchResult.Pending -> prior.copy(phase = SleepSyncPhase.PROCESSING, error = null, nextRetryAt = now + result.retryAfter, serverRetryAt = now + result.retryAfter)
                is SleepFetchResult.Failed -> prior.copy(phase = SleepSyncPhase.ERROR, error = result.category, nextRetryAt = result.retryAt, serverRetryAt = result.retryAt)
            }.copy(lastDataChangedAt = if (merged != old) now else prior.lastDataChangedAt, lastSavedAt = now)
            if (incoming != null) p[logsKey] = json.encodeToString(merged)
            p[statusKey] = json.encodeToString(updated)
        }
        return updated
    }
    suspend fun setRetry(time: Long, count: Int) { data.edit { it[statusKey] = json.encodeToString(status(it).copy(nextRetryAt = time, retryCount = count)) } }
    /** Switch credentials/cache/status together; retain the last legacy snapshot for recovery. */
    suspend fun activateGoogle(credentials: FitbitCredentials, logs: List<SleepLogEntry>, now: Long, processingRetryAt: Long = 0) {
        require(credentials.provider == "google_health" && credentials.accountId.isNotBlank())
        data.edit { p ->
            if (auth(p).provider == "fitbit") {
                p[stringPreferencesKey("legacy_fitbit_credentials")] = json.encodeToString(auth(p))
                p[stringPreferencesKey("legacy_fitbit_sleep_logs")] = json.encodeToString(this.logs(p))
            }
            p[authKey] = json.encodeToString(credentials)
            p[logsKey] = json.encodeToString(AwakePeriods.trim(logs))
            p[statusKey] = json.encodeToString(SleepSyncStatus(lastAttemptAt = now, lastVerifiedAt = if (processingRetryAt > 0) status(p).lastVerifiedAt else now, lastDataChangedAt = now,
                phase = if (processingRetryAt > 0) SleepSyncPhase.PROCESSING else SleepSyncPhase.CURRENT,
                nextRetryAt = processingRetryAt, serverRetryAt = processingRetryAt, lastSavedAt = now,
                connectAttemptAt = now, connectResult = "Connected"))
            p.remove(retryKey); p.remove(notifiedKey)
            p.remove(doublePreferencesKey("bathyphase"))
        }
        AuthNotificationManager(context).cancel()
    }
    suspend fun saveMetrics(bathy: Double?, efficiency: Double, need: Double, provider: String? = null, clearMissingBathy: Boolean = false) { data.edit {
        if (provider != null && auth(it).provider != provider) return@edit
        if (clearMissingBathy && bathy == null) it.remove(doublePreferencesKey("bathyphase"))
        if (bathy != null) it[doublePreferencesKey("bathyphase")] = bathy
        it[doublePreferencesKey("efficiency")] = efficiency
        it[doublePreferencesKey("sleep_need")] = need
    } }
    suspend fun saveOAuth(attempt: OAuthAttempt) { data.edit { it[oauthKey] = json.encodeToString(attempt) } }
    suspend fun consumeOAuth(): OAuthAttempt? {
        var attempt: OAuthAttempt? = null
        data.edit { p -> attempt = p[oauthKey]?.let { try { json.decodeFromString<OAuthAttempt>(it) } catch (_: Exception) { null } }; p.remove(oauthKey) }
        return attempt
    }
    /** Connect failures leave the login unchanged; only the reason is kept for the Sleep tab. */
    suspend fun recordConnectResult(result: String, now: Long) { data.edit { it[statusKey] = json.encodeToString(status(it).copy(connectAttemptAt = now, connectResult = result)) } }
    suspend fun clearAfterLogout() { data.edit { it.remove(oauthKey); it.remove(retryKey); it[statusKey] = json.encodeToString(status(it).copy(phase = SleepSyncPhase.DISCONNECTED, error = null, nextRetryAt = 0)) } }
}

@kotlinx.serialization.Serializable
data class OAuthAttempt(val state: String, val verifier: String, val createdAt: Long)

class FitbitSession private constructor(context: Context) {
    val store = FitbitStore(context)
    val http = FitbitHttpClient(store, com.example.a24_hr_clock.BuildConfig.FITBIT_CLIENT_ID, com.example.a24_hr_clock.BuildConfig.FITBIT_CLIENT_SECRET)
    val googleAuth = GoogleHealthAuthorization(context, store)
    val google = GoogleHealthClient(googleAuth, store::deferUntil, store::retryAt, { store.requireReconnectForProvider("google_health") },
        shapeLog = { android.util.Log.i("HealthShape", it) })
    companion object {
        @Volatile private var instance: FitbitSession? = null
        fun get(context: Context): FitbitSession = instance ?: synchronized(this) {
            instance ?: FitbitSession(context.applicationContext).also { instance = it }
        }
    }
}
