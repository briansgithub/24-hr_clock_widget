package com.example.a24_hr_clock.logic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.a24_hr_clock.logic.DailyPlanClear.Decision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** In-memory DataStore: the file-backed one can't rename files on the Windows test JVM. */
private class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

class DailyClearStoreTest {
    private val settings = SettingsManager(MemoryDataStore())
    private val ny = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ny)
    private val tasks = listOf(PlannedTask("a", "Gym", 420, 60, 0), PlannedTask("b", "Read", 1260, 30, 0))

    @Test fun updateInstalledTodayKeepsTodaysTasksAndClearsAtNextSix() = runBlocking {
        settings.replacePlannedTasks(tasks)
        assertTrue(settings.dailyClearFlow.first().enabled) // on by default

        assertEquals(Decision.ARM, settings.runDailyClear(at(19, 9)))
        assertEquals(tasks, settings.plannedTasksFlow.first())
        assertEquals(Decision.NONE, settings.runDailyClear(at(19, 23)))
        assertEquals(tasks, settings.plannedTasksFlow.first())

        assertEquals(Decision.CLEAR, settings.runDailyClear(at(20, 6)))
        assertEquals(emptyList<PlannedTask>(), settings.plannedTasksFlow.first())
        assertEquals("2026-11-20" to tasks, settings.dailyClearBackupFlow.first())
        assertEquals(Decision.NONE, settings.runDailyClear(at(20, 7)))
    }

    @Test fun turningTheToggleBackOnDoesNotCatchUpEarlierSix() = runBlocking {
        settings.replacePlannedTasks(tasks)
        settings.runDailyClear(at(18, 9))
        settings.setDailyClearEnabled(false, at(18, 10).toInstant().toEpochMilli())
        assertEquals(Decision.NONE, settings.runDailyClear(at(19, 6)))
        settings.setDailyClearEnabled(true, at(19, 8).toInstant().toEpochMilli())
        assertEquals(Decision.NONE, settings.runDailyClear(at(19, 9)))
        assertEquals(tasks, settings.plannedTasksFlow.first())
        assertEquals(Decision.CLEAR, settings.runDailyClear(at(20, 6)))
    }
}
