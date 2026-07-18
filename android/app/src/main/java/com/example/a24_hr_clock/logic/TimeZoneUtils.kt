package com.example.a24_hr_clock.logic

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.math.*

object TimeZoneUtils {

    fun getMostPopulousLocation(zoneId: ZoneId): String {
        val now = java.time.Instant.now()
        val offsetSeconds = zoneId.rules.getOffset(now).totalSeconds
        val offsetHours = offsetSeconds / 3600.0

        // Simple mapping based on the requirement: "City, State" (use state abbreviation) or "City, country"
        return when (offsetHours) {
            -10.0 -> "Honolulu, HI" // Hawaii-Aleutian
            -9.0 -> "Anchorage, AK" // Alaska
            -8.0 -> "Los Angeles, CA" // Pacific
            -7.0 -> "Phoenix, AZ" // Mountain
            -6.0 -> "Chicago, IL" // Central
            -5.0 -> "New York City, NY" // Eastern
            -4.0 -> if (zoneId.id.startsWith("America/")) "New York City, NY" else "San Juan, Puerto Rico"
            -3.0 -> "São Paulo, Brazil" // BRT
            0.0 -> "London, United Kingdom" // GMT/UTC
            1.0 -> if (zoneId.id == "Europe/London") "London, United Kingdom" else "Berlin, Germany" // BST or CET
            2.0 -> if (zoneId.id.contains("Berlin") || zoneId.id.contains("Paris")) "Berlin, Germany" else "Cairo, Egypt" // CEST or EET
            3.0 -> if (zoneId.id.contains("Cairo")) "Cairo, Egypt" else "Moscow, Russia" // EEST or MSK
            5.5 -> "Mumbai, India" // IST
            7.0 -> "Bangkok, Thailand" // ICT
            8.0 -> "Shanghai, China" // CST
            9.0 -> "Tokyo, Japan" // JST
            10.0 -> "Sydney, Australia" // AEST
            11.0 -> "Honiara, Solomon Islands"
            12.0 -> "Auckland, New Zealand"
            else -> "UTC" + (if (offsetHours >= 0) "+" else "") + offsetHours.toString()
        }
    }

    fun getTimeZoneName(zoneId: ZoneId): String {
        val now = java.time.Instant.now()
        val isDaylight = zoneId.rules.isDaylightSavings(now)
        val offsetSeconds = zoneId.rules.getOffset(now).totalSeconds
        val offsetHours = offsetSeconds / 3600.0

        return when (offsetHours) {
            -10.0 -> if (isDaylight) "HDT" else "HST"
            -9.0 -> if (isDaylight) "AKDT" else "AKST"
            -8.0 -> if (isDaylight) "PDT" else "PST"
            -7.0 -> if (isDaylight) "MDT" else "MST"
            -6.0 -> if (isDaylight) "CDT" else "CST"
            -5.0 -> if (isDaylight) "EDT" else "EST"
            -4.0 -> if (isDaylight) "EDT" else "AST"
            0.0 -> "GMT"
            1.0 -> if (isDaylight) "BST" else "CET"
            2.0 -> if (isDaylight) "CEST" else "EET"
            3.0 -> if (isDaylight) "EEST" else "MSK"
            5.5 -> "IST"
            8.0 -> "CST"
            9.0 -> "JST"
            else -> zoneId.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
        }
    }

    fun getUtcTimeZoneStringForOffset(offsetHours: Double): String {
        val roundedOffset = (offsetHours * 2.0).roundToInt() / 2.0
        val totalMinutes = (roundedOffset * 60).roundToInt()
        val hours = totalMinutes / 60
        val minutes = abs(totalMinutes % 60)
        return String.format("UTC%+03d:%02d", hours, minutes)
    }

    fun getMostPopulousLocationForOffset(offsetHours: Double): String {
        val roundedOffset = offsetHours.roundToInt().toDouble()
        
        return when (roundedOffset) {
            -12.0 -> "Baker Island, UM"
            -11.0 -> "Pago Pago, American Samoa"
            -10.0 -> "Honolulu, HI"
            -9.0 -> "Anchorage, AK"
            -8.0 -> "Los Angeles, CA"
            -7.0 -> "Phoenix, AZ"
            -6.0 -> "Chicago, IL"
            -5.0 -> "New York City, NY"
            -4.0 -> "San Juan, Puerto Rico"
            -3.0 -> "São Paulo, Brazil"
            -2.0 -> "South Georgia, GS"
            -1.0 -> "Praia, Cape Verde"
            0.0 -> "London, United Kingdom"
            1.0 -> "Berlin, Germany"
            2.0 -> "Cairo, Egypt"
            3.0 -> "Moscow, Russia"
            4.0 -> "Dubai, UAE"
            5.0 -> "Karachi, Pakistan"
            6.0 -> "Dhaka, Bangladesh"
            7.0 -> "Bangkok, Thailand"
            8.0 -> "Shanghai, China"
            9.0 -> "Tokyo, Japan"
            10.0 -> "Sydney, Australia"
            11.0 -> "Noumea, New Caledonia"
            12.0 -> "Auckland, New Zealand"
            13.0 -> "Nuku'alofa, Tonga"
            14.0 -> "Kiritimati, Kiribati"
            else -> "UTC" + (if (roundedOffset >= 0) "+" else "") + roundedOffset.toInt().toString()
        }
    }

    fun getTimeZoneNameForOffset(offsetHours: Double): String {
        val roundedOffset = offsetHours.roundToInt().toDouble()
        return when (roundedOffset) {
            -10.0 -> "HST"
            -9.0 -> "AKST"
            -8.0 -> "PST"
            -7.0 -> "MST"
            -6.0 -> "CST"
            -5.0 -> "EST"
            -4.0 -> "AST"
            0.0 -> "GMT"
            1.0 -> "CET"
            2.0 -> "EET"
            3.0 -> "MSK"
            4.0 -> "GST"
            5.0 -> "PKT"
            6.0 -> "BST"
            7.0 -> "ICT"
            8.0 -> "CST"
            9.0 -> "JST"
            10.0 -> "AEST"
            11.0 -> "SBT"
            12.0 -> "NZST"
            else -> "GMT" + (if (roundedOffset >= 0) "+" else "") + roundedOffset.toInt().toString()
        }
    }

    fun getUtcTimeZoneString(zoneId: ZoneId): String {
        val offsetSeconds = zoneId.rules.getOffset(java.time.Instant.now()).totalSeconds
        val hours = offsetSeconds / 3600
        val minutes = (abs(offsetSeconds) % 3600) / 60
        return String.format("UTC%+03d:%02d", hours, minutes)
    }

    /**
     * Correlated timezone offset (hours) where sunrise coincides with [wakeHour],
     * matching the Wake-up Offset & Timezone Info calculation.
     */
    fun correlatedTimezoneOffsetHours(wakeHour: Double, sunriseHour: Double): Double {
        val offset = wakeHour - sunriseHour
        val currentOffsetSeconds = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds
        val currentOffsetHours = currentOffsetSeconds / 3600.0
        var normalizedOffset = (currentOffsetHours - offset).roundToInt().toDouble()
        while (normalizedOffset < -12.0) normalizedOffset += 24.0
        while (normalizedOffset > 14.0) normalizedOffset -= 24.0
        return normalizedOffset
    }

    /** Approximate central meridian longitude for a UTC offset (15° per hour), in [-180, 180]. */
    fun longitudeForUtcOffsetHours(offsetHours: Double): Double {
        var lon = offsetHours * 15.0
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }
}
