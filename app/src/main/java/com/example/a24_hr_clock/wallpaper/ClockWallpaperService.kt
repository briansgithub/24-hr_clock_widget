package com.example.a24_hr_clock.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.util.Log
import com.example.a24_hr_clock.logic.*
import kotlinx.coroutines.*
import java.util.*

private const val TAG = "ClockWallpaper"

class ClockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ClockEngine()

    inner class ClockEngine : Engine() {
        private val renderer = ClockRenderer()
        private lateinit var fitbitManager: FitbitManager
        private lateinit var celestialManager: CelestialManager
        private lateinit var settingsManager: SettingsManager
        private lateinit var locationManager: LocationManager

        private var renderJob: Job? = null
        private var dataUpdateJob: Job? = null
        private var settingsJob: Job? = null
        private var locationJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Refresh broadcast received")
                scope.launch {
                    try {
                        updateCelestialData()
                        updateFitbitData()
                        drawFrame()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during manual refresh", e)
                    }
                }
            }
        }

        // Data state
        private var sunriseHour = 6.0
        private var sunsetHour = 18.0
        private var sunRad = 0.0
        private var moonRad = 0.0
        private var moonPhaseValue = 0.0
        private var sleepHour: Double? = null
        private var wakeHour: Double? = null
        private var sleepDebt = 0.0
        private var sleepDuration = 7.5
        private var bathyphaseHour: Double? = null
        private var solarIrradiance = 0

        // Settings
        private var currentSettings = ClockSettings()

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            fitbitManager = FitbitManager(applicationContext)
            settingsManager = SettingsManager(applicationContext)
            locationManager = LocationManager(applicationContext)
            // Initial default location (will be updated)
            celestialManager = CelestialManager(40.7128, -74.0060) // NYC
            
            startDataUpdates()
            observeSettings()
            startLocationUpdates()

            val filter = IntentFilter("com.example.a24_hr_clock.REFRESH_DATA")
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
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

        private fun observeSettings() {
            settingsJob?.cancel()
            settingsJob = scope.launch {
                settingsManager.settingsFlow.collect { settings ->
                    currentSettings = settings
                    if (isVisible) {
                        drawFrame()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
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
                        withContext(Dispatchers.Main) { drawFrame() }
                        
                        updateFitbitData()
                        withContext(Dispatchers.Main) { drawFrame() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Data update loop error", e)
                    }

                    delay(3600000) // Update every hour
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
                val today = java.time.LocalDate.now().toString()
                val start = java.time.LocalDate.now().minusDays(14).toString()
                
                val logs = fitbitManager.fetchSleepLogs(start, today)
                if (logs.isNotEmpty()) {
                    val lastLog = logs.maxByOrNull { it.dateOfSleep }
                    if (lastLog != null) {
                        val startDt = java.time.LocalDateTime.parse(lastLog.startTime.replace("Z", ""))
                        val endDt = java.time.LocalDateTime.parse(lastLog.endTime.replace("Z", ""))
                        sleepHour = startDt.hour + startDt.minute / 60.0
                        wakeHour = endDt.hour + endDt.minute / 60.0
                        sleepDuration = lastLog.minutesAsleep / 60.0
                        
                        // Sleep debt calculation
                        val mappedLogs = logs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
                        sleepDebt = EnergyCalculator.computeSleepDebt(mappedLogs, 9.75) // Goal 9.75
                        
                        // Bathyphase
                        val hrPoints = fitbitManager.fetchHeartRateIntraday(lastLog.dateOfSleep, lastLog.startTime, lastLog.endTime)
                        val mappedHr = hrPoints.map { HeartRatePoint(it.time, it.value) }
                        bathyphaseHour = EnergyCalculator.findBathyphase(mappedHr)
                        Log.d(TAG, "Fitbit data updated successfully. Wake: $wakeHour, Debt: $sleepDebt")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Fitbit data", e)
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
                    sleepHour,
                    wakeHour,
                    sunRad,
                    moonRad,
                    moonPhaseValue,
                    solarIrradiance,
                    sleepDebt,
                    sleepDuration,
                    bathyphaseHour,
                    currentSettings.showNumbers,
                    currentSettings.showSleep,
                    currentSettings.showSunMoon,
                    currentSettings.showSleepDebtText,
                    currentSettings.showEnergy,
                    currentSettings.smallTopRight
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            unregisterReceiver(refreshReceiver)
            stopRendering()
            dataUpdateJob?.cancel()
            settingsJob?.cancel()
            locationJob?.cancel()
        }
    }
}
