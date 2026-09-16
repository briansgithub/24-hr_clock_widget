package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.work.*
import com.example.a24_hr_clock.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class PushStatus(val message: String = "Not configured; polling continues", val registeredAt: Long = 0,
    val receivedAt: Long = 0, val revision: String = "", val token: String = "", val lastCheckedAt: Long = 0,
    val sentAt: Long = 0, val healthy: Boolean = false, val provider: String = "fitbit")

class PushRegistrationManager private constructor(private val context: Context) {
    private val data = context.fitbitDataStore
    private val key = stringPreferencesKey("push_status")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    val statusFlow = data.data.map { p -> try { p[key]?.let { json.decodeFromString<PushStatus>(it) } ?: PushStatus() } catch (_: Exception) { PushStatus() } }
    private suspend fun update(transform: (PushStatus) -> PushStatus) {
        data.edit { p ->
            val current = p[key]?.let { json.decodeFromString<PushStatus>(it) } ?: PushStatus()
            p[key] = json.encodeToString(transform(current))
        }
    }

    suspend fun register(force: Boolean = false) = mutex.withLock {
        if (BuildConfig.SLEEP_RELAY_URL.isBlank()) return@withLock
        val session = FitbitSession.get(context)
        val credentials = session.store.credentials()
        val providerName = if (credentials.provider == "google_health") "Google Health" else "Fitbit"
        if (credentials.accessToken.isBlank()) return@withLock
        if (credentials.reconnectEpisode != null) {
            update { it.copy(message = "Reconnect $providerName to enable push", healthy = false) }
            return@withLock
        }
        val prior = statusFlow.first()
        if (!force && prior.provider == credentials.provider && System.currentTimeMillis() - prior.lastCheckedAt < 60_000) return@withLock
        try {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
                ?: throw IllegalStateException("configuration")
            val auth = FirebaseAuth.getInstance(app)
            // Android restore can copy Firebase auth preferences to a second phone. Keep identities per installation.
            val installation = context.noBackupFilesDir.resolve("sleep_push_installation")
            if (!installation.exists()) auth.signOut()
            if (auth.currentUser == null) auth.signInAnonymously().await()
            if (!installation.exists()) installation.writeText("enrolled")
            val token = FirebaseMessaging.getInstance().token.await()
            val idToken = auth.currentUser!!.getIdToken(false).await().token!!
            if (!force && prior.provider == credentials.provider && prior.token == token && System.currentTimeMillis() - prior.registeredAt < 86_400_000) {
                readStatus(idToken)
                return@withLock
            }
            val healthToken = if (credentials.provider == "google_health") session.googleAuth.accessToken() else session.http.accessToken()
            if (session.store.credentials().provider != credentials.provider) return@withLock
            val body = buildJsonObject {
                put("fcmToken", token); put("provider", credentials.provider)
                put(if (credentials.provider == "google_health") "googleToken" else "fitbitToken", healthToken)
            }
            val response = send("/devices", "POST", idToken, body.toString())
            val subscription = response["subscription"]?.jsonPrimitive?.content ?: "unavailable"
            update { it.copy(message = if (subscription == "active") "Ready" else "Push setup incomplete; polling continues", provider = credentials.provider, registeredAt = System.currentTimeMillis(), lastCheckedAt = System.currentTimeMillis(), token = token, healthy = subscription == "active") }
            readStatus(idToken)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = if (e is FitbitRequestException && e.category == SyncError.AUTH_REQUIRED) "Reconnect $providerName to enable push" else "Push unavailable; polling continues"
            update { it.copy(message = message, lastCheckedAt = System.currentTimeMillis(), healthy = false) }
        }
    }

    suspend fun unregister() = mutex.withLock {
        if (BuildConfig.SLEEP_RELAY_URL.isNotBlank()) try {
            val auth = FirebaseAuth.getInstance()
            auth.currentUser?.getIdToken(false)?.await()?.token?.let { send("/devices", "DELETE", it) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* No sleep or refresh tokens are stored by the relay. */ }
        update { PushStatus(message = "Disconnected; polling resumes after login") }
    }

    suspend fun received(revision: String) { update { it.copy(receivedAt = System.currentTimeMillis(), revision = revision, message = "Sleep update received") } }
    private suspend fun readStatus(idToken: String) {
        val device = send("/status", "GET", idToken)["device"] as? JsonObject
        if (device == null) {
            update { it.copy(message = "Registration missing; polling continues", registeredAt = 0, lastCheckedAt = System.currentTimeMillis(), healthy = false) }
            return
        }
        val subscription = device["subscription_status"]?.jsonPrimitive?.contentOrNull
        val error = device["last_error"]?.jsonPrimitive?.contentOrNull
        val message = when {
            error != null -> "Delivery unavailable; polling continues"
            subscription != "active" -> "Push setup incomplete; polling continues"
            else -> "Ready"
        }
        update { it.copy(message = message, sentAt = device["last_delivery_at"]?.jsonPrimitive?.longOrNull ?: 0, lastCheckedAt = System.currentTimeMillis(), healthy = error == null && subscription == "active") }
    }
    private suspend fun send(path: String, method: String, idToken: String, body: String = ""): JsonObject {
        val url = BuildConfig.SLEEP_RELAY_URL.trimEnd('/')
        check(url.startsWith("https://"))
        val request = Request.Builder().url(url + path).header("Authorization", "Bearer $idToken")
            .method(method, if (method == "POST") body.toRequestBody("application/json".toMediaType()) else null).build()
        return client.newCall(request).awaitResponse().use {
            check(it.isSuccessful) { "relay_${it.code}" }
            json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject
        }
    }
    companion object {
        @Volatile private var instance: PushRegistrationManager? = null
        fun get(context: Context): PushRegistrationManager = instance ?: synchronized(this) {
            instance ?: PushRegistrationManager(context.applicationContext).also { instance = it }
        }
        fun enqueueRegistration(context: Context) {
            if (BuildConfig.SLEEP_RELAY_URL.isBlank()) return
            WorkManager.getInstance(context).enqueueUniqueWork("push_enrollment", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PushRegistrationWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}

class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.SLEEP_RELAY_URL.isBlank() || FitbitSession.get(applicationContext).store.credentials().accessToken.isBlank()) return Result.failure()
        PushRegistrationManager.get(applicationContext).register(force = true)
        if (FitbitSession.get(applicationContext).store.credentials().reconnectEpisode != null) return Result.failure()
        return if (PushRegistrationManager.get(applicationContext).statusFlow.first().healthy) Result.success() else Result.retry()
    }
}

class SleepMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushRegistrationManager.enqueueRegistration(this) }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "registration_refresh") {
            PushRegistrationManager.enqueueRegistration(this)
            return
        }
        if (message.data["type"] != "sleep_invalidated") return
        // Append ensures a second nap's push arriving during a worker cannot be lost.
        WorkManager.getInstance(this).enqueueUniqueWork("sleep_push", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SleepPushWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("revision" to message.data["revision"].orEmpty(), "provider" to (message.data["provider"] ?: "fitbit"))).build())
    }
}

class SleepPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val provider = inputData.getString("provider") ?: "fitbit"
        if (FitbitSession.get(applicationContext).store.credentials().provider != provider) return Result.success()
        PushRegistrationManager.get(applicationContext).received(inputData.getString("revision").orEmpty())
        val result = SleepSyncCoordinator.get(applicationContext).request(history = true, immediate = true).await()
        return if (result is SleepFetchResult.Complete) Result.success() else Result.failure()
    }
}
