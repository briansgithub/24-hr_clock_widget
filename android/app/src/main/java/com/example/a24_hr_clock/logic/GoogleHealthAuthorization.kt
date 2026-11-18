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
            if (identity.healthUserId != current.accountId || identity.legacyUserId != BuildConfig.HEALTH_LEGACY_ACCOUNT)
                throw FitbitRequestException(SyncError.CONFIGURATION)
            store.saveCredentials(current.copy(accessToken = token, expiresAt = System.currentTimeMillis() + 3_500_000))
            token
        } catch (e: CancellationException) { throw e }
          catch (e: ApiException) { throw categorized(e) }
    }

    suspend fun complete(result: AuthorizationResult) = mutex.withLock {
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        if (!result.grantedScopes.containsAll(scopes.map { it.scopeUri })) throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        val account = result.toGoogleSignInAccount()?.account?.name.orEmpty()
        android.util.Log.i("GoogleHealthAuth", "Authorization received: accountHintAvailable=${account.isNotBlank()}")
        val candidate = candidate(token)
        val identity = candidate.identity()
        val current = store.credentials()
        val expected = if (current.provider == "fitbit") current.accountId.ifBlank { BuildConfig.HEALTH_LEGACY_ACCOUNT } else BuildConfig.HEALTH_LEGACY_ACCOUNT
        if (expected.isBlank() || identity.legacyUserId != expected ||
            (current.provider == "google_health" && current.accountId != identity.healthUserId)) throw FitbitRequestException(SyncError.CONFIGURATION)
        val today = LocalDate.now()
        when (val result = candidate.sleep(today.minusDays(14).toString(), today.toString())) {
            is SleepFetchResult.Complete -> store.activateGoogle(FitbitCredentials(accessToken = token, expiresAt = System.currentTimeMillis() + 3_500_000,
                accountId = identity.healthUserId, provider = "google_health", googleAccount = account), result.logs, System.currentTimeMillis())
            is SleepFetchResult.Pending -> throw FitbitRequestException(SyncError.SERVER, System.currentTimeMillis() + result.retryAfter)
            is SleepFetchResult.Failed -> throw FitbitRequestException(result.category, result.retryAt)
        }
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
