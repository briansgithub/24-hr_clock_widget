package com.example.a24_hr_clock.logic

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlannedTaskStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun withStore(test: suspend (SettingsManager, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
            folder.root.resolve("plan.preferences_pb")
        }
        try { test(SettingsManager(store), store) }
        finally { job.cancelAndJoin() }
    }

    @Test fun concurrentAddsAndMovesPreserveOtherEditsAndDeletedTasksStayDeleted() = runBlocking {
        withStore { settings, _ ->
            val first = PlannedTask.create("Read", 540, 60)
            val second = PlannedTask.create("Walk", 600, 30)
            coroutineScope {
                launch { settings.savePlannedTask(first) }
                launch { settings.savePlannedTask(second) }
            }
            assertEquals(2, settings.plannedTasksFlow.first().size)
            val edited = first.copy(name = "Read a book", durationMinutes = 90)
            settings.savePlannedTask(edited)
            settings.movePlannedTask(first.id, 1430)
            assertEquals(edited.copy(startMinute = 1430), settings.plannedTasksFlow.first().find { it.id == first.id })
            assertEquals(second, settings.plannedTasksFlow.first().find { it.id == second.id })
            settings.deletePlannedTask(first.id)
            settings.movePlannedTask(first.id, 100)
            assertEquals(listOf(second), settings.plannedTasksFlow.first())
        }
    }

    @Test fun visibilityIsIndependentAndDisplayResetKeepsPlan() = runBlocking {
        withStore { settings, _ ->
            val task = PlannedTask.create("Work", 540, 60)
            settings.savePlannedTask(task)
            settings.updateHomeSettings(ClockSettings(showTasks = false))
            settings.updateLockSettings(ClockSettings(showTasks = true))
            assertFalse(settings.homeSettingsFlow.first().showTasks)
            assertTrue(settings.lockSettingsFlow.first().showTasks)
            settings.resetHomeSettings()
            assertEquals(listOf(task), settings.plannedTasksFlow.first())
        }
    }

    @Test fun tasksSurviveReopeningAndMissingOrMalformedDataDoesNotCrash() = runBlocking {
        val task = PlannedTask.create("Sleep prep", 1410, 60)
        withStore { settings, store ->
            assertTrue(settings.plannedTasksFlow.first().isEmpty())
            store.edit { it[stringPreferencesKey("planned_tasks_json")] = "invalid JSON" }
            assertTrue(settings.plannedTasksFlow.first().isEmpty())
            settings.savePlannedTask(task)
        }
        withStore { settings, _ -> assertEquals(listOf(task), settings.plannedTasksFlow.first()) }
    }
}
