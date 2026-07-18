package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimeZoneUtilsTest {

    @Test
    fun testGetMostPopulousLocation() {
        val now = java.time.Instant.now()
        val nyOffset = ZoneId.of("America/New_York").rules.getOffset(now).totalSeconds / 3600.0
        val expectedNy = "New York City, NY"
        assertEquals(expectedNy, TimeZoneUtils.getMostPopulousLocation(ZoneId.of("America/New_York")))

        val laOffset = ZoneId.of("America/Los_Angeles").rules.getOffset(now).totalSeconds / 3600.0
        val expectedLa = if (laOffset == -7.0) "Phoenix, AZ" else "Los Angeles, CA"
        assertEquals(expectedLa, TimeZoneUtils.getMostPopulousLocation(ZoneId.of("America/Los_Angeles")))

        assertEquals("Phoenix, AZ", TimeZoneUtils.getMostPopulousLocation(ZoneId.of("America/Phoenix")))
        assertEquals("Shanghai, China", TimeZoneUtils.getMostPopulousLocation(ZoneId.of("Asia/Shanghai")))
        assertEquals("London, United Kingdom", TimeZoneUtils.getMostPopulousLocation(ZoneId.of("Europe/London")))
    }

    @Test
    fun testGetTimeZoneName() {
        // Just verify it returns something sensible for common zones
        val nyName = TimeZoneUtils.getTimeZoneName(ZoneId.of("America/New_York"))
        assertTrue(nyName == "EDT" || nyName == "EST")
        
        assertEquals("GMT", TimeZoneUtils.getTimeZoneName(ZoneId.of("UTC")))
        assertEquals("CST", TimeZoneUtils.getTimeZoneName(ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun testGetUtcTimeZoneString() {
        val now = java.time.Instant.now()
        val nyOffset = ZoneId.of("America/New_York").rules.getOffset(now).totalSeconds / 3600
        val expectedNy = String.format("UTC%+03d:00", nyOffset)
        assertEquals(expectedNy, TimeZoneUtils.getUtcTimeZoneString(ZoneId.of("America/New_York")))

        val laOffset = ZoneId.of("America/Los_Angeles").rules.getOffset(now).totalSeconds / 3600
        val expectedLa = String.format("UTC%+03d:00", laOffset)
        assertEquals(expectedLa, TimeZoneUtils.getUtcTimeZoneString(ZoneId.of("America/Los_Angeles")))

        assertEquals("UTC+00:00", TimeZoneUtils.getUtcTimeZoneString(ZoneId.of("UTC")))
        assertEquals("UTC+08:00", TimeZoneUtils.getUtcTimeZoneString(ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun testLongitudeForUtcOffsetHours() {
        assertEquals(-75.0, TimeZoneUtils.longitudeForUtcOffsetHours(-5.0), 0.001)
        assertEquals(0.0, TimeZoneUtils.longitudeForUtcOffsetHours(0.0), 0.001)
        assertEquals(135.0, TimeZoneUtils.longitudeForUtcOffsetHours(9.0), 0.001)
        assertEquals(180.0, TimeZoneUtils.longitudeForUtcOffsetHours(12.0), 0.001)
        assertEquals(-180.0, TimeZoneUtils.longitudeForUtcOffsetHours(-12.0), 0.001)
    }
}
