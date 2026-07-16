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
        val events = mutableListOf<CalendarEvent>()
        
        // 1. Find all Visible Calendars
        val visibleCalendarIds = getVisibleCalendarIds()
        if (visibleCalendarIds.isEmpty()) {
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
        
        val rawEvents = mutableListOf<RawEvent>()

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
                    if (enabledIds != null) {
                        if (!enabledIds.contains(calId)) continue
                    } else if (visibleCalendarIds.isNotEmpty() && !visibleCalendarIds.contains(calId)) {
                        continue
                    }

                    rawEvents.add(RawEvent(
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

        if (rawEvents.isEmpty()) return emptyList()

        // 3. Process Logic
        // Determine the end of the current event if one is happening
        var currentEventEnd = nowMilli
        val currentEvents = rawEvents.filter { it.begin <= nowMilli && it.end > nowMilli && !it.allDay }
        if (currentEvents.isNotEmpty()) {
            currentEventEnd = currentEvents.maxOf { it.end }
        }

        val windowEnd = currentEventEnd + 24 * 60 * 60 * 1000
        val truncationLimit = nowMilli + 24 * 60 * 60 * 1000

        val zoneId = ZoneId.systemDefault()
        val nowDt = LocalDateTime.ofInstant(now, zoneId)
        val nowHour = nowDt.hour + nowDt.minute / 60.0 + nowDt.second / 3600.0

        rawEvents.forEach { raw ->
            if (raw.allDay) return@forEach

            val isCurrent = raw.begin <= nowMilli && raw.end > nowMilli
            
            // Criteria: 
            // 1. Currently occurring
            // 2. Starts between now and windowEnd
            val shouldInclude = isCurrent || (raw.begin >= nowMilli && raw.begin < windowEnd)
            
            if (shouldInclude) {
                var displayStartMilli = raw.begin
                var displayEndMilli = raw.end

                // "if an event is currently occurring... then show the event arc from now clock hand until its end time."
                if (isCurrent) {
                    displayStartMilli = nowMilli
                }

                // "If an event has a start time between now and now+24h 
                // but an end time after 'now +24h', then only display the event arc 
                // from it's start time up to the clock hand."
                if (!isCurrent && raw.begin < truncationLimit && raw.end > truncationLimit) {
                    displayEndMilli = nowMilli // Clock hand position
                }

                val startDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(displayStartMilli), zoneId)
                val endDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(displayEndMilli), zoneId)
                
                var sH = startDt.hour + startDt.minute / 60.0 + startDt.second / 3600.0
                var eH = endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0

                // Special handling for arcs crossing 24h boundaries or truncation
                // If it's truncated to nowMilli, eH becomes nowHour.
                
                events.add(CalendarEvent(raw.title, sH, eH, raw.allDay, raw.color, isCurrent))
            }
        }

        // Sort so current events are last (drawn on top)
        return events.sortedWith(compareBy({ it.isCurrent }, { it.startHour }))
    }

    private data class RawEvent(
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val color: Int
    )


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
