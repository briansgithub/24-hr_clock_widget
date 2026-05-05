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
        
        val now = Instant.now()
        val startOfDay = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
            .toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val endOfDay = startOfDay + 24 * 60 * 60 * 1000
        
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startOfDay)
        ContentUris.appendId(builder, endOfDay)
        
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_COLOR
        )
        
        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
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
                    
                    val startHour = if (allDay) 0.0 else startDt.hour + startDt.minute / 60.0
                    val endHour = if (allDay) 24.0 else endDt.hour + endDt.minute / 60.0
                    
                    events.add(CalendarEvent(title, startHour, endHour, allDay, color))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarManager", "Error querying calendar", e)
        }
        
        return events
    }
}

data class CalendarEvent(
    val title: String,
    val startHour: Double,
    val endHour: Double,
    val isAllDay: Boolean,
    val color: Int
)
