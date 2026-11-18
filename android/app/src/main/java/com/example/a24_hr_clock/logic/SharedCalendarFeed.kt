package com.example.a24_hr_clock.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One calendar observer for the process, shared by every wallpaper engine. */
object SharedCalendarFeed {
    private const val TAG = "SharedCalendarFeed"
    private const val REFRESH_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val eventsFlow: StateFlow<List<CalendarEvent>> = events

    @Volatile
    private var started = false

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = context.applicationContext
            val manager = CalendarManager(app)
            val settings = SettingsManager(app)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch { refresh(app, manager, settings) }
                }
            }
            try {
                app.contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI,
                    true,
                    observer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register calendar observer", e)
            }
            scope.launch {
                settings.calendarSettingsFlow.collect {
                    refresh(app, manager, settings)
                }
            }
            // Event arcs are placed relative to "now", so recompute them as time passes, not only
            // when calendar data changes. Otherwise ended events stay on the dial (where they read
            // as tomorrow's) and events that enter the next 24 hours never appear.
            scope.launch {
                while (true) {
                    delay(REFRESH_MS - System.currentTimeMillis() % REFRESH_MS)
                    refresh(app, manager, settings)
                }
            }
            started = true
        }
    }

    private suspend fun refresh(app: Context, manager: CalendarManager, settings: SettingsManager) {
        if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            events.value = emptyList()
            return
        }
        val ids = settings.calendarSettingsFlow.first().enabledIds
        events.value = try {
            manager.getTodayEvents(ids)
        } catch (e: Exception) {
            Log.e(TAG, "Calendar refresh failed", e)
            events.value
        }
    }
}
