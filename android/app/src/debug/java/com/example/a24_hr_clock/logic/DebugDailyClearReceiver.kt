package com.example.a24_hr_clock.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Debug-only test hook: runs the daily clear decision as if the clock read `now_millis`.
 * `adb shell am broadcast -a com.example.a24_hr_clock.DEBUG_DAILY_CLEAR -p com.example.a24_hr_clock --el now_millis <ms>`
 * Logs the decision under the "DailyClear" tag. With `now_millis` it doesn't touch the real alarm;
 * without it, it runs the real catch-up and arms the next 6:00.
 */
class DebugDailyClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!intent.hasExtra("now_millis")) {
                    Log.i("DailyClear", "debug catch-up -> ${DailyClearScheduler.catchUpAndArm(context)}")
                    return@launch
                }
                val millis = intent.getLongExtra("now_millis", 0L)
                val now = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                val decision = SettingsManager(context.applicationContext).runDailyClear(now)
                Log.i("DailyClear", "debug run at $now -> $decision")
            } finally { pending.finish() }
        }
    }
}
