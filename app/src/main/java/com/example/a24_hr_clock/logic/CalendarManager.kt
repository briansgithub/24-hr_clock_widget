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
        val startOfDay = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = startOfDay + 24 * 60 * 60 * 1000
        
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startOfDay)
        ContentUris.appendId(builder, endOfDay)
        
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_COLOR,
            CalendarContract.Instances.CALENDAR_ID
        )
        
        // 2. Filter Instances by the Primary Calendar ID
        val selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
        val selectionArgs = arrayOf(primaryCalendarId.toString())

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
                    val title = it.getString(titleIdx) ?: "No Title"
                    val begin = it.getLong(beginIdx)
                    val end = it.getLong(endIdx)
                    val allDay = it.getInt(allDayIdx) != 0
                    val color = it.getInt(colorIdx)
                    
                    val startDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(begin), ZoneId.systemDefault())
                    val endDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneId.systemDefault())
                    
                    val startHour = if (allDay) 0.0 else startDt.hour + startDt.minute / 60.0 + startDt.second / 3600.0
                    val endHour = if (allDay) 24.0 else endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0
                    
                    Log.d("CalendarManager", "Event: $title at ${String.format("%.2f", startHour)}h (AllDay=$allDay)")
                    events.add(CalendarEvent(title, startHour, endHour, allDay, color))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error querying calendar", e)
        }
        
        return events
    }

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
    val color: Int
)
