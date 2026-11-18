package com.example.a24_hr_clock.logic

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val isPrimary: Boolean,
    val color: Int,
    val isVisible: Boolean
)

class CalendarManager(private val context: Context) {

    fun getTodayEvents(enabledIds: Set<Long>? = null): List<CalendarEvent> {
        // 1. Find all Visible Calendars
        val visibleCalendarIds = getVisibleCalendarIds()
        val wanted = enabledIds?.let { CalendarSelection.reconcile(it, getAllCalendars()) }
        if (visibleCalendarIds.isEmpty() && wanted == null) {
            Log.w("CalendarManager", "No visible calendars found")
            return emptyList()
        }

        val now = Instant.now()
        val nowMilli = now.toEpochMilli()
        
        // Query a wider range to catch events that might overlap 'now' or start in the next 48h
        val startQuery = nowMilli - 24 * 60 * 60 * 1000
        val endQuery = nowMilli + 48 * 60 * 60 * 1000
        
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startQuery)
        ContentUris.appendId(builder, endQuery)
        
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_COLOR,
            CalendarContract.Instances.CALENDAR_ID
        )
        
        val rawEvents = mutableListOf<CalendarRawEvent>()

        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                null, // Query all, filter in Kotlin for robustness
                null,
                CalendarContract.Instances.BEGIN + " ASC"
            )
            
            cursor?.use {
                val titleIdx = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = it.getColumnIndex(CalendarContract.Instances.END)
                val allDayIdx = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val colorIdx = it.getColumnIndex(CalendarContract.Instances.EVENT_COLOR)
                val calIdIdx = it.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                
                while (it.moveToNext()) {
                    val calId = if (calIdIdx != -1) it.getLong(calIdIdx) else -1L
                    
                    // Filter by enabled IDs if provided, otherwise fallback to system visibility
                    if (wanted != null) {
                        if (!wanted.contains(calId)) continue
                    } else if (visibleCalendarIds.isNotEmpty() && !visibleCalendarIds.contains(calId)) {
                        continue
                    }

                    rawEvents.add(CalendarRawEvent(
                        title = if (titleIdx != -1) it.getString(titleIdx) ?: "No Title" else "No Title",
                        begin = if (beginIdx != -1) it.getLong(beginIdx) else 0L,
                        end = if (endIdx != -1) it.getLong(endIdx) else 0L,
                        allDay = if (allDayIdx != -1) it.getInt(allDayIdx) != 0 else false,
                        color = if (colorIdx != -1) it.getInt(colorIdx) else 0
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error querying calendar", e)
        }

        return CalendarWindow.build(rawEvents, nowMilli, ZoneId.systemDefault())
    }

    fun getAllCalendars(): List<CalendarInfo> {
        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE
        )
        
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use {
                val idIdx = it.getColumnIndex(CalendarContract.Calendars._ID)
                val nameIdx = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val primaryIdx = it.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val colorIdx = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
                val visibleIdx = it.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                
                while (it.moveToNext()) {
                    calendars.add(CalendarInfo(
                        id = it.getLong(idIdx),
                        name = it.getString(nameIdx) ?: "Unknown",
                        account = it.getString(accountIdx) ?: "Unknown",
                        isPrimary = it.getInt(primaryIdx) != 0,
                        color = it.getInt(colorIdx),
                        isVisible = it.getInt(visibleIdx) != 0
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error querying all calendars", e)
        }
        return calendars
    }

    private fun getVisibleCalendarIds(): List<Long> {
        val ids = mutableListOf<Long>()
        val projection = arrayOf(CalendarContract.Calendars._ID)
        // We look for all visible calendars. 
        // Some users might have their main calendar not marked as 'primary' but still 'visible'.
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    ids.add(it.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error finding visible calendars", e)
        }
        
        if (ids.isEmpty()) {
            Log.d("CalendarManager", "No visible calendars, trying fallback to any account-linked calendar")
            try {
                context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use {
                    if (it.moveToFirst()) ids.add(it.getLong(0))
                }
            } catch (e: Exception) {}
        }

        Log.d("CalendarManager", "Active Calendar IDs: $ids")
        return ids
    }
}

data class CalendarEvent(
    val title: String,
    val startHour: Double,
    val endHour: Double,
    val isAllDay: Boolean,
    val color: Int,
    val isCurrent: Boolean = false
)

data class CalendarRawEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int
)

/** Which events to draw on the 24-hour dial at a given moment. Pure, so it can be tested. */
object CalendarWindow {
    private const val DAY = 24 * 60 * 60 * 1000L

    fun build(rawEvents: List<CalendarRawEvent>, nowMilli: Long, zoneId: ZoneId): List<CalendarEvent> {
        val timed = rawEvents.filter { !it.allDay }
        // An event running now pushes the window out so the next day's events still fit after it.
        val currentEventEnd = timed.filter { it.begin <= nowMilli && it.end > nowMilli }.maxOfOrNull { it.end } ?: nowMilli
        val windowEnd = currentEventEnd + DAY
        val truncationLimit = nowMilli + DAY
        val events = mutableListOf<CalendarEvent>()
        for (raw in timed) {
            val isCurrent = raw.begin <= nowMilli && raw.end > nowMilli
            if (!isCurrent && !(raw.begin >= nowMilli && raw.begin < windowEnd)) continue
            // A current event is drawn from the clock hand to its end.
            val displayStart = if (isCurrent) nowMilli else raw.begin
            // A future event that runs past now+24h is drawn from its start up to the clock hand.
            val displayEnd = if (!isCurrent && raw.begin < truncationLimit && raw.end > truncationLimit) nowMilli else raw.end
            events.add(CalendarEvent(raw.title, hourOf(displayStart, zoneId), hourOf(displayEnd, zoneId), false, raw.color, isCurrent))
        }
        // Current events last, so they are drawn on top.
        return events.sortedWith(compareBy({ it.isCurrent }, { it.startHour }))
    }

    private fun hourOf(milli: Long, zoneId: ZoneId): Double {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(milli), zoneId)
        return t.hour + t.minute / 60.0 + t.second / 3600.0
    }
}

object CalendarSelection {
    /**
     * Calendar row IDs are local to one device. After a backup restore onto a new phone, or after
     * an account is removed and added again, the saved IDs can all point at calendars that no
     * longer exist, which hides every event. Keep the saved IDs that still exist; if none do,
     * fall back to the primary calendars, as on first run. An empty choice stays empty.
     */
    fun reconcile(enabled: Set<Long>, calendars: List<CalendarInfo>): Set<Long> {
        if (enabled.isEmpty() || calendars.isEmpty()) return enabled
        val existing = calendars.map { it.id }.toSet()
        val kept = enabled.filter { it in existing }.toSet()
        return kept.ifEmpty { calendars.filter { it.isPrimary }.map { it.id }.toSet() }
    }
}
