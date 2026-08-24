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
        private val MODEL_SETTINGS_JSON = stringPreferencesKey("model_settings_json")
        private val CALENDAR_SETTINGS_JSON = stringPreferencesKey("calendar_settings_json")

        // DEFAULT SETTINGS TEMPLATES
        private val DEFAULT_HOME_SETTINGS = ClockSettings(
            showNumbers = false,
            showSleep = true,
            showEnergy = true,
            showSunMoon = true,
            showSleepDebtText = true,
            smallTopRight = true,
            showLifeCalendar = false,
            showCalendar = true,
            showTotalBedtime = true,
            showEnergyPct = false,
            normalizeEnergy = false,
            showManualWake = true,
            showBathyphase = true,
            showAcrophase = true,
            showWakeSunriseInfo = false,
            showTimezoneMap = false,
            showGrogginess = true,
            showWindDown = true,
            showBedtimeCountdown = false
        )

        private val DEFAULT_LOCK_SETTINGS = ClockSettings(
            showNumbers = false,
            showSleep = false,
            showEnergy = false,
            showSunMoon = true,
            showSleepDebtText = false,
            smallTopRight = false,
            showLifeCalendar = false,
            showCalendar = true,
            showTotalBedtime = true,
            showEnergyPct = false,
            normalizeEnergy = false,
            showManualWake = true,
            showBathyphase = true,
            showAcrophase = true,
            showWakeSunriseInfo = true,
            showTimezoneMap = true,
            showGrogginess = false,
            showWindDown = false,
            showBedtimeCountdown = false
        )

        private val DEFAULT_MODEL_SETTINGS = ModelSettings(
            bedtimeGoal = 9.75,
            tauWake = 23.0,
            tauSleep = 4.0,
            tauInertia = 1.5,
            debtFactor = 1.0,
            circadianOffset = 12.0,
            useBathyphase = true,
            includeNaps = true,
            manualWakeTime = "09:00",
            excludedDates = emptyList(),
            explicitDates = emptyList(),
            lastTodayDate = "",
            userAge = 31,
            restingHR = 55.0,
            hrvPeakPotential = 71.0,
            hrvMedicatedBase = 30.0,
            googleDriveUrl = ModelSettings.DEFAULT_GOOGLE_DRIVE_WEB_APP_URL,
            localBackupUri = "",
            lastEmpiricalSync = 0L,
            usePersonalModel = true,
            personalModelId = EnergyCalculator.PRODUCTION_MODEL_ID,
            baselineIntercept = 37.30320553378204,
            baselineSleepCoef = 0.042489107249623334,
            baselineDebtCoef = 0.4463493452453248,
            baselineEfficiencyCoef = -0.8018439800808569,
            circAmp = 20.0,
            circAmp2 = 3.2914687807723015,
            circOffset = 4.929056621498718
        )

        private val DEFAULT_CALENDAR_SETTINGS = CalendarSettings()
    }

    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val homeSettingsFlow: Flow<ClockSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[HOME_SETTINGS_JSON]?.let {
            try { json.decodeFromString<ClockSettings>(it) } catch (e: Exception) { DEFAULT_HOME_SETTINGS }
        } ?: DEFAULT_HOME_SETTINGS
    }

    val lockSettingsFlow: Flow<ClockSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[LOCK_SETTINGS_JSON]?.let {
            try { json.decodeFromString<ClockSettings>(it) } catch (e: Exception) { DEFAULT_LOCK_SETTINGS }
        } ?: DEFAULT_LOCK_SETTINGS
    }

    val modelSettingsFlow: Flow<ModelSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[MODEL_SETTINGS_JSON]?.let {
            try { json.decodeFromString<ModelSettings>(it) } catch (e: Exception) { DEFAULT_MODEL_SETTINGS }
        } ?: DEFAULT_MODEL_SETTINGS
    }

    val calendarSettingsFlow: Flow<CalendarSettings> = context.settingsDataStore.data.map { prefs ->
        prefs[CALENDAR_SETTINGS_JSON]?.let {
            try { json.decodeFromString<CalendarSettings>(it) } catch (e: Exception) { DEFAULT_CALENDAR_SETTINGS }
        } ?: DEFAULT_CALENDAR_SETTINGS
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

    suspend fun updateModelSettings(update: ModelSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[MODEL_SETTINGS_JSON] = json.encodeToString(update)
        }
    }

    suspend fun updateCalendarSettings(update: CalendarSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[CALENDAR_SETTINGS_JSON] = json.encodeToString(update)
        }
    }

    suspend fun resetHomeSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs[HOME_SETTINGS_JSON] = json.encodeToString(DEFAULT_HOME_SETTINGS)
        }
    }

    suspend fun resetLockSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs[LOCK_SETTINGS_JSON] = json.encodeToString(DEFAULT_LOCK_SETTINGS)
        }
    }

    suspend fun resetModelSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs[MODEL_SETTINGS_JSON] = json.encodeToString(DEFAULT_MODEL_SETTINGS)
        }
    }

    suspend fun resetCalendarSettings() {
        context.settingsDataStore.edit { prefs ->
            prefs[CALENDAR_SETTINGS_JSON] = json.encodeToString(DEFAULT_CALENDAR_SETTINGS)
        }
    }
}

@Serializable
data class ClockSettings(
    val showNumbers: Boolean = false,
    val showSleep: Boolean = false,
    val showEnergy: Boolean = false,
    val showSunMoon: Boolean = true,
    val showSleepDebtText: Boolean = false,
    val smallTopRight: Boolean = false,
    val showLifeCalendar: Boolean = false,
    val showCalendar: Boolean = true,
    val showTotalBedtime: Boolean = true,
    val showEnergyPct: Boolean = false,
    val normalizeEnergy: Boolean = false,
    val showManualWake: Boolean = true,
    val showBathyphase: Boolean = true,
    val showAcrophase: Boolean = true,
    val showWakeSunriseInfo: Boolean = false,
    val showTimezoneMap: Boolean = false,
    val showGrogginess: Boolean = false,
    val showWindDown: Boolean = false,
    val showBedtimeCountdown: Boolean = false
)

@Serializable
data class CalendarSettings(
    val enabledIds: Set<Long> = emptySet(),
    val initialized: Boolean = false
)

@Serializable
data class ModelSettings(
    val bedtimeGoal: Double = 9.75,
    val tauWake: Double = 23.0,
    val tauSleep: Double = 4.0,
    val tauInertia: Double = 1.5,
    val debtFactor: Double = 1.0,
    val circadianOffset: Double = 12.0,
    val useBathyphase: Boolean = true,
    val includeNaps: Boolean = true,
    val manualWakeTime: String = "09:00",
    val excludedDates: List<String> = emptyList(),
    val explicitDates: List<String> = emptyList(),
    val lastTodayDate: String = "",
    val userAge: Int = 31,
    val restingHR: Double = 55.0,
    val maxHR: Double = 189.0, // 220 - 31
    val isMale: Boolean = true,
    val hrvPeakPotential: Double = 71.0,
    val hrvMedicatedBase: Double = 30.0,
    val googleDriveUrl: String = DEFAULT_GOOGLE_DRIVE_WEB_APP_URL,
    val localBackupUri: String = "",
    val lastEmpiricalSync: Long = 0L,
    /** When true, use the generated production adaptive model instead of population defaults. */
    val usePersonalModel: Boolean = true,
    val personalModelId: String = EnergyCalculator.PRODUCTION_MODEL_ID,
    val baselineIntercept: Double = 37.30320553378204,
    val baselineSleepCoef: Double = 0.042489107249623334,
    val baselineDebtCoef: Double = 0.4463493452453248,
    val baselineEfficiencyCoef: Double = -0.8018439800808569,
    val circAmp: Double = 20.0,
    val circAmp2: Double = 3.2914687807723015,
    val circOffset: Double = 4.929056621498718
) {
    companion object {
        const val DEFAULT_GOOGLE_DRIVE_WEB_APP_URL =
            "https://script.google.com/macros/s/AKfycby_x-wS3XfqWZSLgmS69OUZDr30xp5KdcNuM3uH78A6hHMs4URCUm22tz48PXdp_bsi/exec"
    }
}

