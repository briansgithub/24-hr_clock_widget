package com.example.a24_hr_clock.logic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val Context.settingsDataStore by preferencesDataStore(name = "clock_settings")

class SettingsManager(private val context: Context) {

    companion object {
        private val HOME_SETTINGS_JSON = stringPreferencesKey("home_settings_json")
        private val LOCK_SETTINGS_JSON = stringPreferencesKey("lock_settings_json")

        // DEFAULT SETTINGS TEMPLATES
        // Edit these to change the initial appearance for new users
        private val DEFAULT_HOME_SETTINGS = ClockSettings(
            showNumbers = false,
            showSleep = true,
            showEnergy = true,
            showSunMoon = true,
            showSleepDebtText = true,
            smallTopRight = true
        )

        private val DEFAULT_LOCK_SETTINGS = ClockSettings(
            showNumbers = false,
            showSleep = false,
            showEnergy = false,
            showSunMoon = true,
            showSleepDebtText = false,
            smallTopRight = false

        )
    }

    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val homeSettingsFlow: Flow<ClockSettings> = context.settingsDataStore.data.map { prefs ->
        val jsonStr = prefs[HOME_SETTINGS_JSON]
        if (jsonStr != null) {
            try {
                json.decodeFromString<ClockSettings>(jsonStr)
            } catch (e: Exception) {
                DEFAULT_HOME_SETTINGS
            }
        } else {
            DEFAULT_HOME_SETTINGS
        }
    }

    val lockSettingsFlow: Flow<ClockSettings> = context.settingsDataStore.data.map { prefs ->
        val jsonStr = prefs[LOCK_SETTINGS_JSON]
        if (jsonStr != null) {
            try {
                json.decodeFromString<ClockSettings>(jsonStr)
            } catch (e: Exception) {
                DEFAULT_LOCK_SETTINGS
            }
        } else {
            DEFAULT_LOCK_SETTINGS
        }
    }


    suspend fun updateHomeSettings(update: ClockSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[HOME_SETTINGS_JSON] = json.encodeToString(update)
        }
    }

    suspend fun updateLockSettings(update: ClockSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[LOCK_SETTINGS_JSON] = json.encodeToString(update)
        }
    }
}

@Serializable
data class ClockSettings(
    val showNumbers: Boolean = false,
    val showSleep: Boolean = true,
    val showEnergy: Boolean = true,
    val showSunMoon: Boolean = true,
    val showSleepDebtText: Boolean = true,
    val smallTopRight: Boolean = false
)

