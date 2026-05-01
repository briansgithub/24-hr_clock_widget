package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "clock_settings")

class SettingsManager(private val context: Context) {

    companion object {
        val SHOW_NUMBERS = booleanPreferencesKey("show_numbers")
        val SHOW_SLEEP = booleanPreferencesKey("show_sleep")
        val SHOW_ENERGY = booleanPreferencesKey("show_energy")
        val SHOW_SUN_MOON = booleanPreferencesKey("show_sun_moon")
        val SHOW_SLEEP_DEBT_TEXT = booleanPreferencesKey("show_sleep_debt_text")
        val SMALL_TOP_RIGHT = booleanPreferencesKey("small_top_right")
    }

    val settingsFlow: Flow<ClockSettings> = context.settingsDataStore.data.map { prefs ->
        ClockSettings(
            showNumbers = prefs[SHOW_NUMBERS] ?: false,
            showSleep = prefs[SHOW_SLEEP] ?: true,
            showEnergy = prefs[SHOW_ENERGY] ?: true,
            showSunMoon = prefs[SHOW_SUN_MOON] ?: true,
            showSleepDebtText = prefs[SHOW_SLEEP_DEBT_TEXT] ?: true,
            smallTopRight = prefs[SMALL_TOP_RIGHT] ?: false
        )
    }

    suspend fun updateSettings(update: ClockSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[SHOW_NUMBERS] = update.showNumbers
            prefs[SHOW_SLEEP] = update.showSleep
            prefs[SHOW_ENERGY] = update.showEnergy
            prefs[SHOW_SUN_MOON] = update.showSunMoon
            prefs[SHOW_SLEEP_DEBT_TEXT] = update.showSleepDebtText
            prefs[SMALL_TOP_RIGHT] = update.smallTopRight
        }
    }
}

data class ClockSettings(
    val showNumbers: Boolean = false,
    val showSleep: Boolean = true,
    val showEnergy: Boolean = true,
    val showSunMoon: Boolean = true,
    val showSleepDebtText: Boolean = true,
    val smallTopRight: Boolean = false
)
