package com.example.a24_hr_clock.logic

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarManager(private val context: Context) {

    fun getTodayEvents(): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        
        // 1. Find the Primary Calendar ID
        val primaryCalendarId = getPrimaryCalendarId() ?: return emptyList()

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
        
        val selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
        val selectionArgs = arrayOf(primaryCalendarId.toString())

        val rawEvents = mutableListOf<RawEvent>()

        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                selection,
                selectionArgs,
                CalendarContract.Instances.BEGIN + " ASC"
            )
            
            cursor?.use {
                val titleIdx = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = it.getColumnIndex(CalendarContract.Instances.END)
                val allDayIdx = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val colorIdx = it.getColumnIndex(CalendarContract.Instances.EVENT_COLOR)
                
                while (it.moveToNext()) {
                    rawEvents.add(RawEvent(
                        title = it.getString(titleIdx) ?: "No Title",
                        begin = it.getLong(beginIdx),
                        end = it.getLong(endIdx),
                        allDay = it.getInt(allDayIdx) != 0,
                        color = it.getInt(colorIdx)
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


    private fun getPrimaryCalendarId(): Long? {
        // We look for the main calendar associated with the primary Google account
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        // IS_PRIMARY = 1 is the most reliable way to find the main user calendar
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = 1 AND ${CalendarContract.Calendars.VISIBLE} = 1"
        
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    val account = it.getString(1)
                    val name = it.getString(2)
                    Log.d("CalendarManager", "Found Primary Calendar: ID=$id, Account=$account, Name=$name")
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error finding primary calendar", e)
        }
        
        // Fallback: If no IS_PRIMARY, take the first visible one that looks like an email address
        val fallbackSelection = "${CalendarContract.Calendars.VISIBLE} = 1"
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                fallbackSelection,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val account = it.getString(1)
                    if (account.contains("@")) {
                        val id = it.getLong(0)
                        Log.d("CalendarManager", "Fallback Calendar: ID=$id, Account=$account")
                        return id
                    }
                }
            }
        } catch (e: Exception) {}

        return null
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
