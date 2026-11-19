package com.example.a24_hr_clock.logic

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.example.a24_hr_clock.BuildConfig
import java.time.LocalDate

/** Google Play services owns renewal. Only foreground code may launch a returned resolution. */
class GoogleHealthAuthorization(context: Context, private val store: FitbitStore) : CredentialProvider {
    val client = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()
    private val scopes = listOf("sleep.readonly", "health_metrics_and_measurements.readonly")
        .map { Scope("https://www.googleapis.com/auth/googlehealth.$it") }
    private fun request(account: String = "") = AuthorizationRequest.builder().setRequestedScopes(scopes).apply {
        if (account.isNotBlank()) setAccount(Account(account, "com.google"))
    }.build()

    suspend fun begin(): AuthorizationResult = try {
        val current = store.credentials()
        client.authorize(request(if (current.provider == "google_health") current.googleAccount else "")).await()
    } catch (e: CancellationException) { throw e }
      catch (e: ApiException) { throw categorized(e) }

    override suspend fun accessToken(rejectedToken: String?): String = mutex.withLock {
        val current = store.credentials()
        if (current.provider != "google_health" || current.reconnectEpisode != null)
            throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        if (current.expiresAt > System.currentTimeMillis() + 60_000 && current.accessToken.isNotBlank() &&
            (rejectedToken == null || rejectedToken != current.accessToken)) return@withLock current.accessToken
        try {
            if (rejectedToken != null) client.clearToken(ClearTokenRequest.builder().setToken(rejectedToken).build()).await()
            val result = client.authorize(request(current.googleAccount)).await()
            if (result.hasResolution() || result.accessToken.isNullOrBlank() || !result.grantedScopes.containsAll(scopes.map { it.scopeUri })) {
                store.requireReconnectForProvider("google_health")
                throw FitbitRequestException(SyncError.AUTH_REQUIRED)
            }
            val token = result.accessToken!!
            // Health-only authorization need not return an email/account profile.
            // Validate renewed tokens by Health identity even when the SDK selects its default account.
            val identity = candidate(token).identity()
            if (!HealthIdentityRules.renewalMatches(identity, current.accountId, BuildConfig.HEALTH_LEGACY_ACCOUNT))
                throw FitbitRequestException(SyncError.CONFIGURATION)
            store.saveCredentials(current.copy(accessToken = token, expiresAt = System.currentTimeMillis() + 3_500_000))
            token
        } catch (e: CancellationException) { throw e }
          catch (e: ApiException) { throw categorized(e) }
    }

    suspend fun complete(result: AuthorizationResult) = mutex.withLock {
        val now = System.currentTimeMillis()
        // Each refusal is logged and kept in the sync status: a toast and logcat both vanish too fast to debug.
        suspend fun fail(reason: String, category: SyncError, retryAt: Long = 0): Nothing {
            android.util.Log.w("GoogleHealthAuth", "Connect failed: $reason ($category)")
            store.recordConnectResult("Failed: $reason", now)
            throw FitbitRequestException(category, retryAt)
        }
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: fail("Google returned no access token", SyncError.AUTH_REQUIRED)
        val missing = scopes.map { it.scopeUri } - result.grantedScopes.toSet()
        if (missing.isNotEmpty()) fail("Health access not granted for ${missing.joinToString { it.substringAfterLast('.') }}", SyncError.AUTH_REQUIRED)
        val account = result.toGoogleSignInAccount()?.account?.name.orEmpty()
        android.util.Log.i("GoogleHealthAuth", "Authorization received: accountHintAvailable=${account.isNotBlank()}")
        val candidate = candidate(token)
        val identity = try { candidate.identity() } catch (e: CancellationException) { throw e }
            catch (e: FitbitRequestException) { fail("Could not read the Google Health identity", e.category, e.retryAt) }
            catch (e: Exception) { fail("Unreadable Google Health identity", SyncError.DECODING) }
        val current = store.credentials()
        HealthIdentityRules.connectMismatch(identity, current.provider, current.accountId, BuildConfig.HEALTH_LEGACY_ACCOUNT)
            ?.let { fail(it, SyncError.CONFIGURATION) }
        val today = LocalDate.now()
        val start = today.minusDays(14).toString()
        val end = today.toString()
        val fetched = candidate.sleep(start, end)
        val (logs, processingRetryAt) = HealthConnectRules.activation(fetched, now)
            ?: (fetched as SleepFetchResult.Failed).let { fail("Could not read sleep", it.category, it.retryAt) }
        // Google may still be processing last night; the login is valid, so keep it and let sync finish the data.
        val saved = if (processingRetryAt > 0) SleepCache.merge(store.snapshot().logs, logs, start, end, false) else logs
        store.activateGoogle(FitbitCredentials(accessToken = token, expiresAt = now + 3_500_000,
            accountId = identity.healthUserId, provider = "google_health", googleAccount = account), saved, now, processingRetryAt)
        android.util.Log.i("GoogleHealthAuth", "Connected: sleepProcessing=${processingRetryAt > 0}")
    }

    private fun candidate(token: String) = GoogleHealthClient(object : CredentialProvider {
        override suspend fun accessToken(rejectedToken: String?): String {
            if (rejectedToken != null) throw FitbitRequestException(SyncError.AUTH_REQUIRED)
            return token
        }
    })

    private fun categorized(e: ApiException): FitbitRequestException {
        android.util.Log.w("GoogleHealthAuth", "SDK authorization failed: status=${e.statusCode}")
        return FitbitRequestException(when (e.statusCode) {
        CommonStatusCodes.NETWORK_ERROR -> SyncError.NETWORK
        CommonStatusCodes.DEVELOPER_ERROR -> SyncError.CONFIGURATION
        else -> SyncError.SERVER // SDK availability/cancellation does not establish revoked consent.
        })
    }
}

/**
 * Which Health identity a token may belong to. The stored Health user ID was verified when the
 * account was first connected, so renewal and reconnect only need it to match. The build-time
 * legacy account is an extra check when it is set; a build without it (a fresh worktree with no
 * local.properties) must not break renewal, or sleep sync silently stops an hour after install.
 */
object HealthIdentityRules {
    fun renewalMatches(identity: HealthIdentity, storedHealthUserId: String, configuredLegacy: String): Boolean =
        storedHealthUserId.isNotBlank() && identity.healthUserId == storedHealthUserId &&
            (configuredLegacy.isBlank() || identity.legacyUserId == configuredLegacy)

    /** Why [connectMatches] refused, in words for the Sleep tab; null when the account matches. */
    fun connectMismatch(identity: HealthIdentity, provider: String, storedAccountId: String, configuredLegacy: String): String? = when {
        connectMatches(identity, provider, storedAccountId, configuredLegacy) -> null
        provider == "google_health" && storedAccountId.isBlank() -> "No stored Google Health user to match"
        provider == "google_health" && identity.healthUserId != storedAccountId -> "A different Google Health user signed in"
        provider != "google_health" && storedAccountId.ifBlank { configuredLegacy }.isBlank() ->
            "This build has no HEALTH_LEGACY_ACCOUNT to check the account against"
        else -> "The Google account is not the Fitbit account this app was set up with"
    }

    fun connectMatches(identity: HealthIdentity, provider: String, storedAccountId: String, configuredLegacy: String): Boolean =
        if (provider == "google_health") renewalMatches(identity, storedAccountId, configuredLegacy)
        else {
            // First move from Fitbit: the legacy account must be known, from the Fitbit login or the build.
            val expected = storedAccountId.ifBlank { configuredLegacy }
            expected.isNotBlank() && identity.legacyUserId == expected
        }
}

object HealthConnectRules {
    /**
     * Sleep to save when a verified login connects, and when to check again if Google is still
     * processing some of it (0 = complete). Null only when sleep could not be read at all.
     */
    fun activation(result: SleepFetchResult, now: Long): Pair<List<SleepLogEntry>, Long>? = when (result) {
        is SleepFetchResult.Complete -> result.logs to 0L
        is SleepFetchResult.Pending -> result.partialLogs to now + result.retryAfter
        is SleepFetchResult.Failed -> null
    }
}
