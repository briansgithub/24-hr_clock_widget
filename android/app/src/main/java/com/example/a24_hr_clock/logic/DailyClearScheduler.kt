package com.example.a24_hr_clock.logic

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Runs the daily 6:00 plan clear. An alarm fires near 6:00; app start, the wallpaper
 * becoming visible, boot, and clock or time zone changes all catch up a missed one.
 * The once-per-day rule lives in [DailyPlanClear], so extra calls are harmless.
 */
object DailyClearScheduler {
    private const val TAG = "DailyClear"
    const val ACTION_CLEAR = "com.example.a24_hr_clock.DAILY_PLAN_CLEAR"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Clears if a 6:00 is due, then arms (or cancels) the next alarm. */
    suspend fun catchUpAndArm(context: Context, now: ZonedDateTime = ZonedDateTime.now()): DailyPlanClear.Decision {
        val app = context.applicationContext
        val settings = SettingsManager(app)
        val decision = settings.runDailyClear(now)
        if (decision == DailyPlanClear.Decision.CLEAR) Log.i(TAG, "Cleared the plan for ${DailyPlanClear.latestDue(now).toLocalDate()}")
        arm(app, settings.dailyClearFlow.first().enabled, now)
        return decision
    }

    fun launchCatchUp(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { catchUpAndArm(app) }.onFailure { Log.w(TAG, "Catch-up failed", it) } }
    }

    private fun arm(context: Context, enabled: Boolean, now: ZonedDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 6,
            Intent(context, DailyClearReceiver::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!enabled) { alarms.cancel(pending); return }
        // Inexact but allowed in Doze, like the bedtime reminder; the catch-ups cover any delay.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            DailyPlanClear.nextTrigger(now).toInstant().toEpochMilli(), pending)
    }
}

class DailyClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { DailyClearScheduler.catchUpAndArm(context) }
            catch (e: Exception) { Log.w("DailyClear", "Clear failed on ${intent.action}", e) }
            finally { pending.finish() }
        }
    }
}
