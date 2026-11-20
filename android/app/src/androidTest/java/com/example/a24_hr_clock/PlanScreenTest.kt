package com.example.a24_hr_clock

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.a24_hr_clock.logic.ClockDial
import com.example.a24_hr_clock.logic.ClockSettings
import com.example.a24_hr_clock.logic.ModelSettings
import com.example.a24_hr_clock.logic.CelestialManager
import com.example.a24_hr_clock.logic.PlannedTask
import com.example.a24_hr_clock.ui.PlanScreen
import com.example.a24_hr_clock.ui.taskWedgeDrag
import com.example.a24_hr_clock.wallpaper.ClockRenderer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

class PlanScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addEditValidateAndDeleteTask() {
        val tasks = mutableStateListOf<PlannedTask>()
        compose.setContent {
            MaterialTheme {
                PlanScreen(tasks, onSave = { task ->
                    val index = tasks.indexOfFirst { it.id == task.id }
                    if (index < 0) tasks.add(task) else tasks[index] = task
                }, onDelete = { id -> tasks.removeAll { it.id == id } }, clock = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("new-task-start").assertTextEquals("--:--")
        compose.onNodeWithTag("new-task-name").performClick().performTextInput("Read")
        selectTime("new-task-start", "11", "30", "PM")
        compose.runOnIdle { assertTrue(tasks.isEmpty()) } // a name and only one time
        compose.onNodeWithTag("new-task-duration").performClick()
        chooseDial(0, 0, true)
        compose.onNodeWithText("Set").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(tasks.isEmpty()) }
        compose.onNodeWithTag("new-task-duration").performClick()
        chooseDial(2, 0, true)
        compose.onNodeWithText("Set").performClick() // the second time adds the task
        compose.runOnIdle {
            assertEquals(1410, tasks.single().startMinute)
            assertEquals(120, tasks.single().durationMinutes)
        }
        compose.onNodeWithTag("new-task-start").assertTextEquals("--:--")
        compose.onNodeWithTag("new-task-name").assertTextEquals("")
        val id = tasks.single().id
        compose.onNodeWithTag("task-end-$id").assertTextEquals("1:30 AM") // 23:30 + 2 h wraps
        // Existing rows save as soon as a time is picked, updating rather than duplicating.
        selectTime("task-end-$id", "2", "00", "AM")
        compose.onNodeWithTag("task-duration-$id").assertTextEquals("2:30")
        val color = tasks.single().color
        compose.onNodeWithTag("task-duration-$id").performClick()
        chooseDial(3, 0, true)
        compose.onNodeWithText("Set").performClick()
        compose.runOnIdle {
            assertEquals(180, tasks.single().durationMinutes)
            assertEquals(color, tasks.single().color)
        }
        selectTime("task-start-$id", "12", "00", "AM")
        compose.runOnIdle {
            assertEquals(1, tasks.size)
            assertEquals(0, tasks.single().startMinute)
            assertEquals(180, tasks.single().durationMinutes)
        }
        compose.onNodeWithTag("task-name-$id").performTextReplacement("Read a book")
        compose.runOnIdle { tasks[0] = tasks[0].copy(startMinute = 600) }
        compose.onNodeWithTag("task-name-$id").assertTextEquals("Read a book")
        compose.onNodeWithTag("task-start-$id").assertTextEquals("10:00 AM")
        compose.runOnIdle { assertEquals("Read", tasks.single().name) } // not saved mid-typing
        compose.onNodeWithTag("task-name-$id").performImeAction()
        compose.runOnIdle {
            assertEquals("Read a book", tasks.single().name)
            assertEquals(600, tasks.single().startMinute)
        }
        compose.onNodeWithTag("task-name-$id").performTextReplacement("  ")
        compose.onNodeWithText("Enter a task name.").assertExists()
        compose.onNodeWithTag("task-name-$id").performImeAction()
        compose.runOnIdle { assertEquals("Read a book", tasks.single().name) }
        // Times first, then the name: Enter adds it. Start and end give the duration.
        selectTime("new-task-start", "1", "00", "PM")
        selectTime("new-task-end", "2", "15", "PM")
        compose.onNodeWithTag("new-task-duration").assertTextEquals("1:15")
        compose.runOnIdle { assertEquals(1, tasks.size) }
        compose.onNodeWithTag("new-task-name").performTextInput("Walk")
        compose.onNodeWithTag("new-task-name").performImeAction()
        compose.runOnIdle {
            assertEquals(2, tasks.size)
            assertEquals("Walk", tasks[1].name)
            assertEquals(13 * 60, tasks[1].startMinute)
            assertEquals(75, tasks[1].durationMinutes)
        }
        compose.runOnIdle { tasks.removeAt(1) }
        compose.onNodeWithContentDescription("Delete Read a book").performClick()
        compose.onNodeWithTag("task-$id").assertDoesNotExist()
        compose.onNodeWithTag("new-task-row").assertExists()
        compose.onNodeWithText("Start").assertExists()
        compose.onNodeWithText("Dur.").assertExists()
        compose.onNodeWithText("End").assertExists()
        compose.onNodeWithText("Drag a wedge around the clock to change its start time. Durations stay fixed.").assertDoesNotExist()
        compose.onNodeWithText("Tasks stay visible in this editor. Choose Home and Lock Screen visibility in Display.").assertDoesNotExist()
        compose.onNodeWithText("No tasks yet. Add a task with a start time and estimated duration.").assertDoesNotExist()
    }

    @Test fun clockDialogsShowDials() {
        compose.setContent { MaterialTheme { PlanScreen(emptyList(), {}, {}, clock = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("new-task-start").performClick()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.cacheDir.resolve("clock-dial-semantics.txt").writeText(compose.onNodeWithTag("selector-clock").printToString())
        fun screenshot(name: String) {
            val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.cacheDir.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        screenshot("start-dial.png")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("new-task-duration").performClick()
        compose.onNodeWithTag("duration-dial").assertExists()
        compose.onNodeWithContentDescription("12 hours").assertExists()
        screenshot("duration-dial.png")
        compose.onNodeWithTag("duration-mode").performClick()
        compose.onNodeWithTag("duration-typed").performTextReplacement("24:01")
        compose.onNodeWithText("Set").assertIsNotEnabled()
        compose.onNodeWithTag("duration-typed").performTextReplacement("24:00")
        compose.onNodeWithText("Set").performClick()
        compose.onNodeWithTag("new-task-duration").assertTextEquals("24:00")
    }

    private fun selectTime(tag: String, hour: String, minute: String, period: String) {
        compose.onNodeWithTag(tag).performClick()
        chooseDial(hour.toInt(), minute.toInt(), false)
        compose.onNodeWithText(period).performClick()
        compose.onNodeWithText("Set").performClick()
    }

    private fun chooseDial(hour: Int, minute: Int, duration: Boolean) {
        compose.onNode(hasContentDescription(if (duration) "$hour hours" else "$hour o'clock") and !hasContentDescription("Select hour", substring = true)).performClick()
        compose.onNodeWithContentDescription("Select minutes", substring = true).performClick()
        val minuteNode = hasContentDescription("$minute minutes") and !hasContentDescription("Select minutes", substring = true)
        compose.waitUntil(timeoutMillis = 3000) { compose.onAllNodes(minuteNode).fetchSemanticsNodes().size == 1 }
        compose.onNode(minuteNode).performClick()
    }

    @Test fun wedgeDragCrossesMidnightWithoutChangingDurationOrColor() {
        val original = PlannedTask("task", "Read", 1410, 90, 0xFF63C5DA.toInt())
        var committed: PlannedTask? = null
        compose.setContent {
            var preview by remember { mutableStateOf<PlannedTask?>(null) }
            Canvas(Modifier.size(300.dp).testTag("dial").taskWedgeDrag(
                tasks = listOf(original), enabled = true,
                geometry = { ClockDial(it.width / 2f, it.height / 2f, it.width / 2f) },
                onPreview = { preview = it }, onCommit = { committed = it }
            )) { drawCircle(androidx.compose.ui.graphics.Color((preview ?: original).color)) }
        }
        compose.onNodeWithTag("dial").performTouchInput {
            fun at(minute: Double): Offset {
                val angle = Math.toRadians(minute / 4.0 - 270.0)
                return center + Offset((width * 0.35 * cos(angle)).toFloat(), (width * 0.35 * sin(angle)).toFloat())
            }
            down(at(1425.0))
            moveTo(at(45.0), delayMillis = 300)
            up()
        }
        compose.runOnIdle {
            assertEquals(original.copy(startMinute = 30), committed)
        }
    }

    @Test fun cancelledDragDoesNotSaveAndClearsPreview() {
        val task = PlannedTask("task", "Work", 1080, 120, 0xFF63C5DA.toInt())
        var preview: PlannedTask? = null
        var committed = false
        compose.setContent {
            Canvas(Modifier.size(300.dp).testTag("dial").taskWedgeDrag(listOf(task), true,
                geometry = { ClockDial(it.width / 2f, it.height / 2f, it.width / 2f) },
                onPreview = { preview = it }, onCommit = { committed = true })) {}
        }
        compose.onNodeWithTag("dial").performTouchInput {
            down(center + Offset(width * 0.35f, height * 0.08f))
            moveTo(center + Offset(width * 0.2f, height * 0.3f))
            cancel()
        }
        compose.runOnIdle { assertFalse(committed); assertNull(preview) }
    }

    @Test fun embeddedClockKeepsPlanControlsVisible() {
        val tasks = listOf(PlannedTask("task", "Reading", 540, 90, 0xFF63C5DA.toInt()))
        compose.setContent {
            MaterialTheme {
                Surface {
                    PlanScreen(tasks, onSave = {}, onDelete = {}) { modifier ->
                        ClockPreviewScreen(settings = ClockSettings(showNumbers = true),
                            modelSettings = ModelSettings(), sleepLogs = emptyList(),
                            metrics = Triple(null, 1.0, 9.75), sunTimes = 6.0 to 18.0,
                            celestialPositions = CelestialManager.SunMoonPosition(0.0, 0.0, 0.0, 0.0),
                            solarIrradiance = 0, calendarEvents = emptyList(), sleepDebt = 0.0,
                            plannedTasks = tasks, title = "", showSetWallpaper = false,
                            compact = true, modifier = modifier)
                    }
                }
            }
        }
        val button = compose.onNodeWithText("Plan").captureToImage().asAndroidBitmap()
        val pixels = IntArray(button.width * button.height)
        button.getPixels(pixels, 0, button.width, 0, 0, button.width, button.height)
        assertTrue("Clock must not clear the controls drawn above its bounds", pixels.any {
            AndroidColor.red(it) > 30 || AndroidColor.green(it) > 30 || AndroidColor.blue(it) > 30
        })
        val snapshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.cacheDir.resolve("planner-preview.png").outputStream().use {
            snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun taskOverlayIsAboveClockFaceAndBelowNowHandAndCanBeHidden() {
        fun render(showTasks: Boolean): Bitmap {
            val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            ClockRenderer().draw(NativeCanvas(image), 400, 400,
                Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) },
                sunriseHour = 6.0, sunsetHour = 18.0, sunRad = 0.0, moonRad = 0.0,
                moonPhaseValue = 0.0, sunElevation = 0.0, solarIrradiance = 0, sleepDebt = 0.0,
                bathyphaseHour = null, showSleep = false, showSunMoon = false, showSleepDebtText = false,
                showCalendar = false, showManualWake = false, showWakeSunriseInfo = false,
                dialMarginPx = 20f, centerDial = true, showTasks = showTasks,
                plannedTasks = listOf(PlannedTask("task", "Work", 1020, 120, 0xFF63C5DA.toInt())))
            return image
        }
        val hidden = render(false)
        val shown = render(true)
        assertNotEquals(hidden.getPixel(300, 180), shown.getPixel(300, 180))
        assertEquals(hidden.getPixel(100, 200), shown.getPixel(100, 200))
        assertEquals(hidden.getPixel(300, 200), shown.getPixel(300, 200))
        assertEquals(AndroidColor.parseColor("#FF9F1C"), shown.getPixel(300, 200))
    }
}
