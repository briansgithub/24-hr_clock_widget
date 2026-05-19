package com.example.a24_hr_clock.wallpaper

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.util.Log
import com.example.a24_hr_clock.logic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.util.*

private const val TAG = "ClockWallpaper"

private data class DataSnapshot(
    val settings: ClockSettings,
    val model: ModelSettings,
    val calendar: CalendarSettings,
    val logs: List<SleepLogEntry>,
    val debt: Double,
    val bathy: Double?
)

class ClockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ClockEngine()

    inner class ClockEngine : Engine() {
        private val renderer = ClockRenderer()
        private lateinit var fitbitManager: FitbitManager
        private lateinit var calendarManager: CalendarManager
        private lateinit var celestialManager: CelestialManager
        private lateinit var settingsManager: SettingsManager
        private lateinit var locationManager: LocationManager

        private var renderJob: Job? = null
        private var dataUpdateJob: Job? = null
        private var settingsJob: Job? = null
        private var locationJob: Job? = null
        private var calendarObserver: android.database.ContentObserver? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Refresh broadcast received")
                scope.launch {
                    try {
                        updateCelestialData()
                        updateFitbitData()
                        updateCalendarData()
                        drawFrame()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during manual refresh", e)
                    }
                }
            }
        }

        private val lockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Lock state broadcast received: ${intent?.action}")
                updateLockState()
            }
        }

        // Data state
        private var sunriseHour = 6.0
        private var sunsetHour = 18.0
        private var sunRad = 0.0
        private var moonRad = 0.0
        private var moonPhaseValue = 0.0
        private var sleepLogs = emptyList<SleepLogEntry>()
        private var sleepDebt = 0.0
        private var bathyphaseHour: Double? = null
        private var calendarEvents = emptyList<CalendarEvent>()
        private var solarIrradiance = 0

        // Settings
        private var currentSettings = ClockSettings()
        private var modelSettings = ModelSettings()
        private var calendarSettings = CalendarSettings()
        private val isLockedState = MutableStateFlow(false)
        private var previewLockScreen = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            fitbitManager = FitbitManager(applicationContext)
            calendarManager = CalendarManager(applicationContext)
            settingsManager = SettingsManager(applicationContext)
            locationManager = LocationManager(applicationContext)
            // Initial default location (will be updated)
            celestialManager = CelestialManager(40.7128, -74.0060) // NYC
            
            updateLockState()
            startDataUpdates()
            observeAllData()
            startLocationUpdates()

            val filter = IntentFilter("com.example.a24_hr_clock.REFRESH_DATA")
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)

            val lockFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(lockStateReceiver, lockFilter)

            calendarObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    Log.d(TAG, "Calendar provider changed, updating events")
                    scope.launch(Dispatchers.IO) {
                        updateCalendarData()
                        withContext(Dispatchers.Main) { drawFrame() }
                    }
                }
            }
            try {
                applicationContext.contentResolver.registerContentObserver(
                    android.provider.CalendarContract.Events.CONTENT_URI,
                    true,
                    calendarObserver!!
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register calendar observer", e)
            }
        }

        private fun updateLockState() {
            if (isPreview) {
                isLockedState.value = previewLockScreen
                return
            }
            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            isLockedState.value = km.isKeyguardLocked
        }

        override fun onTouchEvent(event: android.view.MotionEvent?) {
            super.onTouchEvent(event)
            if (isPreview && event?.action == android.view.MotionEvent.ACTION_DOWN) {
                previewLockScreen = !previewLockScreen
                updateLockState()
            }
        }

        private fun startLocationUpdates() {
            locationJob?.cancel()
            locationJob = scope.launch {
                try {
                    // 1. Get last known location immediately for instant draw
                    val lastLoc = locationManager.getLastKnownLocation()
                    if (lastLoc != null) {
                        Log.d(TAG, "Using last known location: $lastLoc")
                        celestialManager = CelestialManager(lastLoc.first, lastLoc.second)
                        updateCelestialData()
                        drawFrame()
                    }

                    while (isActive) {
                        // 2. Refine with current location in background
                        val currentLoc = try {
                            locationManager.getCurrentLocation()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Location permission missing during update")
                            null
                        }
                        
                        if (currentLoc != null) {
                            Log.d(TAG, "Updated with precise location: $currentLoc")
                            celestialManager = CelestialManager(currentLoc.first, currentLoc.second)
                            updateCelestialData()
                            drawFrame()
                        }
                        delay(3600000) // Update location every hour
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Location update job failed", e)
                }
            }
        }

        private fun observeAllData() {
            settingsJob?.cancel()
            settingsJob = scope.launch {
                val uiFlow = combine(
                    settingsManager.homeSettingsFlow,
                    settingsManager.lockSettingsFlow,
                    isLockedState
                ) { home, lock, isLocked ->
                    if (isLocked) lock else home
                }

                combine(
                    uiFlow,
                    settingsManager.modelSettingsFlow,
                    settingsManager.calendarSettingsFlow,
                    fitbitManager.sleepLogsFlow,
                    fitbitManager.metricsFlow
                ) { ui, model, cal, logs, metrics ->
                    val (_, _, sleepNeed) = metrics
                    val mappedLogs = logs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
                    val debt = EnergyCalculator.computeSleepDebt(mappedLogs, sleepNeed, model.includeNaps, model.excludedDates)
                    DataSnapshot(ui, model, cal, logs, debt, metrics.first)
                }.collect { snapshot ->
                    currentSettings = snapshot.settings
                    modelSettings = snapshot.model
                    calendarSettings = snapshot.calendar
                    sleepLogs = snapshot.logs
                    sleepDebt = snapshot.debt
                    bathyphaseHour = snapshot.bathy
                    if (isVisible) {
                        drawFrame()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                updateLockState()
                startRendering()
            } else {
                stopRendering()
            }
        }


        private fun startRendering() {
            renderJob?.cancel()
            renderJob = scope.launch {
                while (isActive) {
                    drawFrame()
                    delay(10000) // Update every 10 seconds for efficiency (hand movement)
                }
            }
        }

        private fun stopRendering() {
            renderJob?.cancel()
        }

        private fun startDataUpdates() {
            dataUpdateJob?.cancel()
            dataUpdateJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        updateCelestialData()
                        updateFitbitData()
                        updateCalendarData()
                        withContext(Dispatchers.Main) { drawFrame() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Data update loop error", e)
                    }

                    delay(600000) // Update every 10 minutes
                }
            }
        }

        private fun updateCelestialData() {
            val (sunrise, sunset) = celestialManager.getSunTimes()
            sunriseHour = sunrise
            sunsetHour = sunset
            
            val (sRad, mRad, mPhase) = celestialManager.getCelestialPositions()
            sunRad = sRad
            moonRad = mRad
            moonPhaseValue = mPhase

            solarIrradiance = celestialManager.getSolarIrradiance()
        }

        private suspend fun updateFitbitData() {
            try {
                val today = java.time.LocalDate.now()
                val todayStr = today.toString()
                val startStr = today.minusDays(14).toString()
                
                val logs = fitbitManager.fetchSleepLogs(startStr, todayStr)
                if (logs.isNotEmpty()) {
                    // Parity with Python: 
                    // 1. Try to find TODAY'S main sleep record
                    var targetLog = logs.filter { it.dateOfSleep == todayStr && it.isMainSleep }
                        .maxByOrNull { it.endTime }
                    
                    val isRealToday = targetLog != null
                    
                    // 2. Fallback to the LATEST main sleep record available (usually yesterday)
                    if (targetLog == null) {
                        targetLog = logs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep }
                        Log.d(TAG, "Today's sleep missing, falling back to: ${targetLog?.dateOfSleep}")
                    }
                    
                    if (targetLog != null) {
                        sleepLogs = logs
                        
                        // Efficiency and Sleep Need (calculated from full 14-day history)
                        val totalAsleepMins = logs.sumOf { it.minutesAsleep }
                        val totalBedMins = logs.sumOf { it.timeInBed }
                        val empiricalEfficiency = if (totalBedMins > 0) totalAsleepMins.toDouble() / totalBedMins.toDouble() else 0.92
                        val sleepNeedHours = modelSettings.bedtimeGoal * empiricalEfficiency

                        // Auto-toggle today's date if no data yet (exclude it so it doesn't skew debt)
                        val hasTodayLog = logs.any { it.dateOfSleep == todayStr }
                        var newExcludedDates = modelSettings.excludedDates
                        if (hasTodayLog && newExcludedDates.contains(todayStr)) {
                            newExcludedDates = newExcludedDates.filter { it != todayStr }
                        } else if (!hasTodayLog && !newExcludedDates.contains(todayStr)) {
                            newExcludedDates = newExcludedDates + todayStr
                        }
                        if (newExcludedDates != modelSettings.excludedDates) {
                            settingsManager.updateModelSettings(modelSettings.copy(excludedDates = newExcludedDates))
                            modelSettings = modelSettings.copy(excludedDates = newExcludedDates)
                        }

                        // Sleep debt calculation (weighted 14-day window)
                        val mappedLogs = logs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
                        sleepDebt = EnergyCalculator.computeSleepDebt(mappedLogs, sleepNeedHours, modelSettings.includeNaps, modelSettings.excludedDates)
                        
                        // Bathyphase (from the target session)
                        val hrPoints = fitbitManager.fetchHeartRateIntraday(targetLog.startTime, targetLog.endTime)
                        val mappedHr = hrPoints.map { HeartRatePoint(it.time, it.value) }
                        bathyphaseHour = EnergyCalculator.findBathyphase(mappedHr)
                        
                        // Notify managers/UI
                        fitbitManager.saveMetrics(bathyphaseHour, empiricalEfficiency, sleepNeedHours)
                        
                        Log.d(TAG, "Fitbit Sync: Date=${targetLog.dateOfSleep}, RealToday=$isRealToday, Debt=$sleepDebt")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Fitbit data", e)
            }
        }

        private fun updateCalendarData() {
            if (applicationContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                calendarEvents = calendarManager.getTodayEvents(calendarSettings.enabledIds)
                Log.d(TAG, "Calendar data updated: ${calendarEvents.size} events")
            }
        }

        private fun drawFrame() {
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                renderer.draw(
                    canvas, canvas.width, canvas.height,
                    Calendar.getInstance(),
                    sunriseHour,
                    sunsetHour,
                    sleepLogs,
                    sunRad,
                    moonRad,
                    moonPhaseValue,
                    solarIrradiance,
                    sleepDebt,
                    bathyphaseHour,
                    calendarEvents,
                    currentSettings.showNumbers,
                    currentSettings.showSleep,
                    currentSettings.showSunMoon,
                    currentSettings.showSleepDebtText,
                    currentSettings.showEnergy,
                    currentSettings.showCalendar,
                    currentSettings.smallTopRight,
                    currentSettings.showLifeCalendar,
                    currentSettings.showTotalBedtime,
                    currentSettings.showEnergyPct,
                    currentSettings.normalizeEnergy,
                    modelSettings.includeNaps,
                    modelSettings.tauWake,
                    modelSettings.tauSleep,
                    modelSettings.tauInertia,
                    modelSettings.debtFactor,
                    modelSettings.circadianOffset,
                    modelSettings.useBathyphase,
                    modelSettings.bedtimeGoal,
                    currentSettings.showManualWake,
                    modelSettings.manualWakeTime,
                    isPreview,
                    previewLockScreen
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            unregisterReceiver(refreshReceiver)
            unregisterReceiver(lockStateReceiver)
            calendarObserver?.let {
                applicationContext.contentResolver.unregisterContentObserver(it)
            }
            stopRendering()
            dataUpdateJob?.cancel()
            settingsJob?.cancel()
            locationJob?.cancel()
        }
    }
}
