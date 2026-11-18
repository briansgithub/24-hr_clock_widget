package com.example.a24_hr_clock.logic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PlannedTaskTest {
    private val task = PlannedTask("task", "Read", 23 * 60 + 30, 90, 0xFF63C5DA.toInt())
    private val dial = ClockDial(250f, 300f, 200f)

    private fun point(minute: Double, radius: Float = 150f): Pair<Float, Float> {
        val angle = Math.toRadians(minute / 4.0 - 270.0)
        return dial.centerX + radius * cos(angle).toFloat() to dial.centerY + radius * sin(angle).toFloat()
    }

    @Test fun midnightTaskContainsBothSidesButExcludesEnd() {
        assertTrue(task.containsMinute(1439.0))
        assertTrue(task.containsMinute(0.0))
        assertTrue(task.containsMinute(59.9))
        assertFalse(task.containsMinute(60.0))
        assertFalse(task.containsMinute(1409.0))
        assertTrue(task.copy(durationMinutes = 1440).containsMinute(600.0))
    }

    @Test fun hitTestingUsesTopmostWedgeAndExcludesCenterAndOutsideDial() {
        val overlap = task.copy(id = "top", startMinute = 0, durationMinutes = 30)
        val p = point(15.0)
        assertEquals(overlap, dial.taskAt(p.first, p.second, listOf(task, overlap)))
        assertNull(dial.taskAt(dial.centerX, dial.centerY, listOf(task)))
        val outside = point(15.0, 210f)
        assertNull(dial.taskAt(outside.first, outside.second, listOf(task)))
        val empty = point(600.0)
        assertNull(dial.taskAt(empty.first, empty.second, listOf(task)))
    }

    @Test fun rotationPreservesGrabOffsetAcrossMidnightInBothDirections() {
        val clockwise = point(45.0)
        assertEquals(30, dial.rotatedStart(task, 1425.0, clockwise.first, clockwise.second))
        val counterclockwise = point(1425.0)
        val early = task.copy(startMinute = 15)
        assertEquals(1410, dial.rotatedStart(early, 30.0, counterclockwise.first, counterclockwise.second))
        assertEquals(task.startMinute, dial.rotatedStart(task, 1425.0, dial.centerX, dial.centerY))
    }

    @Test fun cardinalClockHoursMatchExistingDialOrientation() {
        assertEquals(0.0, dial.minuteAt(250f, 450f)!!, 0.001) // Midnight at bottom.
        assertEquals(360.0, dial.minuteAt(100f, 300f)!!, 0.001)
        assertEquals(720.0, dial.minuteAt(250f, 150f)!!, 0.001)
        assertEquals(1080.0, dial.minuteAt(400f, 300f)!!, 0.001)
    }

    @Test fun smallAndLargeClocksUseTheirRenderedGeometry() {
        assertEquals(ClockDial(500f, 512.5f, 350f),
            ClockDial.create(1000, 1200, false, 100f, 50f, 150f))
        assertEquals(ClockDial(800f, 250f, 150f),
            ClockDial.create(1000, 1200, true, 100f, 50f, 100f))
        assertEquals(ClockDial(200f, 150f, 130f),
            ClockDial.create(400, 300, false, 100f, 50f, 20f, centered = true))
    }

    @Test fun inputRejectsInvalidTimesAndDurations() {
        assertEquals(0, PlannedTask.parseStartTime("00:00"))
        assertEquals(1439, PlannedTask.parseStartTime("23:59"))
        assertEquals(540, PlannedTask.parseStartTime("9:00"))
        listOf("24:00", "12:60", "9:0", "-1:00", "09", "abc").forEach {
            assertNull(it, PlannedTask.parseStartTime(it))
        }
        assertFalse(task.copy(durationMinutes = 0).isValid)
        assertFalse(task.copy(durationMinutes = 1441).isValid)
        assertFalse(task.copy(startMinute = 1440).isValid)
        assertFalse(task.copy(name = " ").isValid)
    }

    @Test fun serializationAndEditsKeepAssignedColorAndIdentity() {
        val created = PlannedTask.create(" Work ", 540, 60)
        assertEquals("Work", created.name)
        assertTrue(created.isValid)
        assertEquals(255, created.color ushr 24)
        val edited = created.copy(startMinute = 600, durationMinutes = 90)
        assertEquals(created.color, edited.color)
        assertEquals(created.id, edited.id)
        assertEquals(listOf(edited), Json.decodeFromString<List<PlannedTask>>(Json.encodeToString(listOf(edited))))
        assertTrue(Json.decodeFromString<ClockSettings>("{}").showTasks)
    }
}
