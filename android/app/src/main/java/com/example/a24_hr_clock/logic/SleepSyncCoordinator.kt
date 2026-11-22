package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** One process-wide queue: requests received during a fetch form the next batch. */
class SleepRequestQueue(private val scope: CoroutineScope, private val fetch: suspend (Boolean, Boolean) -> SleepFetchResult) {
    private val monitor = Any()
    private var running = false
    private var full = false
    private var immediate = false
    private var waiting = mutableListOf<CompletableDeferred<SleepFetchResult>>()
    fun request(history: Boolean, force: Boolean): Deferred<SleepFetchResult> = synchronized(monitor) {
        val result = CompletableDeferred<SleepFetchResult>()
        full = full || history; immediate = immediate || force; waiting.add(result)
        if (!running) {
            running = true
            scope.launch {
                while (true) {
                    val batch = synchronized(monitor) {
                        if (waiting.isEmpty()) { running = false; null }
                        else Triple(full, immediate, waiting).also { full = false; immediate = false; waiting = mutableListOf() }
                    } ?: break
                    try {
                        val value = fetch(batch.first, batch.second)
                        batch.third.forEach { it.complete(value) }
                    } catch (e: Throwable) {
                        batch.third.forEach { it.completeExceptionally(e) }
                        if (e is CancellationException) throw e
                    }
                }
            }
        }
        result
    }
}

class SleepSyncCoordinator private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = FitbitSession.get(context).store
    private val manager = FitbitManager(context)
    private val queue = SleepRequestQueue(scope, ::sync)
    private val visible = mutableSetOf<String>()
    private var visibleJob: Job? = null
    fun request(history: Boolean = false, immediate: Boolean = false) = queue.request(history, immediate)

    @Synchronized fun setVisible(surface: String, value: Boolean) {
        if (value) visible.add(surface) else visible.remove(surface)
        if (visible.isEmpty()) { visibleJob?.cancel(); visibleJob = null }
        else {
            // Visibility always checks the 30-second threshold, even when another surface is visible.
            request()
            scope.launch { store.ensureAuthNotification() }
            if (visibleJob == null) visibleJob = scope.launch {
                while (isActive) { delay(120_000); request() }
            }
        }
    }

    private suspend fun sync(history: Boolean, immediate: Boolean): SleepFetchResult {
        val before = store.snapshot()
        if (before.auth.accessToken.isBlank()) return SleepFetchResult.Failed(SyncError.AUTH_REQUIRED)
        val now = System.currentTimeMillis()
        val deadline = maxOf(store.retryAt(), before.status.serverRetryAt, if (immediate) 0 else before.status.nextRetryAt)
        if (deadline > now) {
            scheduleRetry(deadline, history)
            return SleepFetchResult.Failed(before.status.error ?: SyncError.RATE_LIMIT, deadline)
        }
        if (!immediate && now - before.status.lastAttemptAt < 30_000) {
            return when (before.status.phase) {
                SleepSyncPhase.CURRENT -> SleepFetchResult.Complete(before.logs)
                SleepSyncPhase.PROCESSING -> SleepFetchResult.Pending(before.logs, maxOf(1000, before.status.nextRetryAt - now))
                else -> SleepFetchResult.Failed(before.status.error ?: SyncError.NETWORK, before.status.nextRetryAt)
            }
        }
        val today = LocalDate.now()
        val start = today.minusDays(if (history) 14 else 1).toString()
        val end = today.toString()
        val burstEnd = now + 60_000
        while (true) {
            store.beginAttempt(System.currentTimeMillis())
            val result = withTimeoutOrNull(maxOf(1, burstEnd - System.currentTimeMillis())) {
                manager.fetchSleepLogs(start, end)
            } ?: SleepFetchResult.Failed(SyncError.NETWORK)
            val savedAt = System.currentTimeMillis()
            val status = store.record(result, start, end, savedAt, before.auth.provider)
            Log.i("SleepSync", "saved=${result.javaClass.simpleName} elapsedMs=${savedAt - now} category=${status.error ?: "none"}")
            when (result) {
                is SleepFetchResult.Complete -> {
                    // Publication above precedes all derived metrics and other network work.
                    scope.launch { updateSleepSettings() }
                    return result
                }
                is SleepFetchResult.Pending -> {
                    if (savedAt + result.retryAfter <= burstEnd) { delay(result.retryAfter); continue }
                    val step = status.retryCount.coerceAtMost(2)
                    val retry = savedAt + maxOf(result.retryAfter, longArrayOf(60_000, 300_000, 900_000)[step])
                    store.setRetry(retry, step + 1); scheduleRetry(retry, history)
                    return result
                }
                is SleepFetchResult.Failed -> {
                    if (result.category !in setOf(SyncError.AUTH_REQUIRED, SyncError.CONFIGURATION)) {
                        val step = status.retryCount.coerceAtMost(4)
                        val retry = maxOf(result.retryAt, savedAt + minOf(900_000L, 30_000L * (1L shl step)))
                        store.setRetry(retry, step + 1); scheduleRetry(retry, history)
                    }
                    return result
                }
            }
        }
    }

    private suspend fun updateSleepSettings() {
        val logs = store.snapshot().logs
        val settings = SettingsManager(context)
        val model = settings.modelSettingsFlow.first()
        val today = LocalDate.now().toString()
        if (today !in model.explicitDates) {
            val excluded = if (logs.any { it.dateOfSleep == today && it.isMainSleep }) model.excludedDates - today else (model.excludedDates + today).distinct()
            if (excluded != model.excludedDates) settings.updateModelSettings(model.copy(excludedDates = excluded))
        }
        val main = logs.filter { it.isMainSleep }
        val efficiency = if (main.isEmpty()) 0.92 else main.map { if (it.timeInBed > 0) it.minutesAsleep.toDouble() / it.timeInBed else .92 }.average()
        store.saveMetrics(null, efficiency, model.bedtimeGoal * efficiency)
    }

    private fun scheduleRetry(at: Long, history: Boolean) {
        val work = OneTimeWorkRequestBuilder<SleepSyncWorker>()
            .setInitialDelay(maxOf(1000, at - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("history" to history)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sleep_processing_retry", ExistingWorkPolicy.REPLACE, work)
    }

    companion object {
        @Volatile private var instance: SleepSyncCoordinator? = null
        fun get(context: Context): SleepSyncCoordinator = instance ?: synchronized(this) {
            instance ?: SleepSyncCoordinator(context.applicationContext).also { instance = it }
        }
    }
}

class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = SleepSyncCoordinator.get(applicationContext).request(
            history = inputData.getBoolean("history", true), immediate = inputData.getBoolean("push", false)
        ).await()
        return when (result) {
            is SleepFetchResult.Complete -> Result.success()
            is SleepFetchResult.Pending -> Result.failure(workDataOf("sleepOutcome" to "processing"))
            is SleepFetchResult.Failed -> Result.failure(workDataOf("sleepOutcome" to result.category.name))
        }
    }
}
