package com.example.a24_hr_clock.logic

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class FitbitCredentials(
    val accessToken: String = "", val refreshToken: String = "", val expiresAt: Long = 0,
    val accountId: String = "", val provider: String = "fitbit", val reconnectEpisode: String? = null,
    val dismissedEpisode: String? = null,
    val googleAccount: String = ""
)

interface FitbitCredentialStore {
    suspend fun credentials(): FitbitCredentials
    suspend fun saveCredentials(credentials: FitbitCredentials)
    suspend fun requireReconnectForProvider(provider: String) { if (credentials().provider == provider) requireReconnect() }
    suspend fun requireReconnect()
    suspend fun retryAt(): Long
    suspend fun deferUntil(time: Long)
}

class FitbitRequestException(val category: SyncError, val retryAt: Long = 0) : IOException(category.name)
data class FitbitHttpResult(val code: Int, val body: String, val headers: Headers)

/** One instance per process, including all workers and wallpaper engines. */
class FitbitHttpClient(
    private val store: FitbitCredentialStore,
    private val clientId: String,
    private val clientSecret: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://api.fitbit.com",
    private val now: () -> Long = System::currentTimeMillis
) {
    private val tokenMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun accessToken(rejectedToken: String? = null): String = tokenMutex.withLock {
        val current = store.credentials()
        if (current.provider != "fitbit") throw FitbitRequestException(SyncError.CONFIGURATION)
        if (current.reconnectEpisode != null) throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        if (current.accessToken.isBlank()) throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        val retry = store.retryAt()
        if (retry > now()) throw FitbitRequestException(SyncError.RATE_LIMIT, retry)
        if (rejectedToken != null && rejectedToken != current.accessToken && current.expiresAt > now()) return@withLock current.accessToken
        if (rejectedToken == null && current.expiresAt > now() + 60_000) return@withLock current.accessToken
        if (current.refreshToken.isBlank()) {
            store.requireReconnectForProvider("fitbit")
            throw FitbitRequestException(SyncError.AUTH_REQUIRED)
        }
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", current.refreshToken).build()
        val response = exchange(tokenRequest(form))
        if (response.code !in 200..299) fail(response, refreshing = true)
        val tokens = decodeTokens(response.body)
        // A rotated token must be persisted even if the caller stops displaying the screen.
        withContext(NonCancellable) { store.saveCredentials(tokens) }
        tokens.accessToken
    }

    suspend fun request(path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): FitbitHttpResult {
        val deadline = store.retryAt()
        if (deadline > now()) throw FitbitRequestException(SyncError.RATE_LIMIT, deadline)
        var token = accessToken()
        fun requestWith(value: String): Request {
            val b = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $value")
            headers.forEach { (key, entry) -> b.header(key, entry) }
            if (method == "POST") b.post(FormBody.Builder().build())
            if (method == "DELETE") b.delete()
            return b.build()
        }
        var result = exchange(requestWith(token))
        if (result.code == 401) {
            token = accessToken(token)
            result = exchange(requestWith(token))
        }
        if (result.code !in 200..299) fail(result)
        return result
    }

    suspend fun exchangeCode(code: String, verifier: String, redirect: String) = tokenMutex.withLock {
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
            .add("redirect_uri", redirect).add("code", code).add("code_verifier", verifier).build()
        val result = exchange(tokenRequest(form))
        if (result.code !in 200..299) fail(result, authorization = true)
        withContext(NonCancellable) { store.saveCredentials(decodeTokens(result.body)) }
    }

    suspend fun clearCredentials() = tokenMutex.withLock { store.saveCredentials(FitbitCredentials()) }

    private fun tokenRequest(form: FormBody): Request {
        if (clientId.isBlank() || clientSecret.isBlank()) throw FitbitRequestException(SyncError.CONFIGURATION)
        return Request.Builder().url("$baseUrl/oauth2/token").post(form)
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())).build()
    }

    private fun decodeTokens(body: String): FitbitCredentials = try {
        val t = json.decodeFromString<FitbitTokens>(body)
        if (t.access_token.isBlank() || t.refresh_token.isBlank() || t.expires_in <= 0) throw IllegalArgumentException()
        FitbitCredentials(t.access_token, t.refresh_token, now() + t.expires_in * 1000L, t.user_id)
    } catch (_: Exception) { throw FitbitRequestException(SyncError.DECODING) }

    private suspend fun fail(result: FitbitHttpResult, refreshing: Boolean = false, authorization: Boolean = false): Nothing {
        val type = try {
            val value = json.parseToJsonElement(result.body).jsonObject
            value["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("errorType")?.jsonPrimitive?.content
                ?: value["error"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        val category = when {
            result.code == 429 -> SyncError.RATE_LIMIT
            result.code >= 500 -> SyncError.SERVER
            type in setOf("invalid_client", "unauthorized_client", "unsupported_grant_type") -> SyncError.CONFIGURATION
            (refreshing && type in setOf("invalid_grant", "invalid_token", "expired_token")) ||
                result.code == 401 || type == "insufficient_scope" || authorization -> SyncError.AUTH_REQUIRED
            else -> SyncError.CONFIGURATION
        }
        val retryAt = if (category == SyncError.RATE_LIMIT) {
            val seconds = result.headers["Retry-After"]?.toLongOrNull()
                ?: result.headers["fitbit-rate-limit-reset"]?.toLongOrNull() ?: 60L
            (now() + seconds.coerceAtLeast(1) * 1000L).also { store.deferUntil(it) }
        } else 0L
        if (category == SyncError.AUTH_REQUIRED) store.requireReconnectForProvider("fitbit")
        throw FitbitRequestException(category, retryAt)
    }

    private suspend fun exchange(request: Request): FitbitHttpResult = try {
        client.newCall(request).awaitResponse().use { FitbitHttpResult(it.code, it.body?.string().orEmpty(), it.headers) }
    } catch (e: FitbitRequestException) { throw e }
      catch (_: IOException) { throw FitbitRequestException(SyncError.NETWORK) }
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isCancelled) response.close()
            else continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
