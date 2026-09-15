package com.example.a24_hr_clock.wallpaper

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.util.Log
import com.example.a24_hr_clock.logic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.util.*
import kotlin.math.max
import kotlin.math.min

private const val TAG = "ClockWallpaper"

private data class DataSnapshot(
    val settings: ClockSettings,
    val model: ModelSettings,
    val calendar: CalendarSettings,
    val logs: List<SleepLogEntry>,
    val debt: Double,
    val bathy: Double?,
    val sync: SleepSyncStatus,
    val reconnect: Boolean
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
        private var countdownJob: Job? = null
        private var dataUpdateJob: Job? = null
        private var settingsJob: Job? = null
        private var locationJob: Job? = null
        private var calendarObserver: android.database.ContentObserver? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        /** Prior countdown content bounds; unioned with the next dirty clear to erase shrinking glyphs. */
        private var lastCountdownDirty: Rect? = null

        private val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Refresh broadcast received")
                scope.launch {
                    try {
                        updateCelestialData()
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
        private var sunElevation = 0.0
        private var sleepLogs = emptyList<SleepLogEntry>()
        private var sleepStatus = SleepSyncStatus()
        private var reconnectRequired = false
        private val sleepSurface = "wallpaper-${UUID.randomUUID()}"
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
            renderer.ensureMapBitmap(applicationContext.resources)
            
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
                    fitbitManager.sleepSnapshotFlow,
                    fitbitManager.metricsFlow
                ) { ui, model, cal, sleep, metrics ->
                    val logs = sleep.logs
                    val (_, _, sleepNeed) = metrics
                    val mappedLogs = logs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
                    val debt = EnergyCalculator.computeSleepDebt(mappedLogs, sleepNeed, model.includeNaps, model.excludedDates)
                    DataSnapshot(ui, model, cal, logs, debt, metrics.first, sleep.status, sleep.auth.reconnectEpisode != null)
                }.collect { snapshot ->
                    currentSettings = snapshot.settings
                    modelSettings = snapshot.model
                    calendarSettings = snapshot.calendar
                    sleepLogs = snapshot.logs
                    sleepStatus = snapshot.sync
                    reconnectRequired = snapshot.reconnect
                    sleepDebt = snapshot.debt
                    bathyphaseHour = snapshot.bathy
                    if (isVisible) {
                        drawFrame()
                        startRendering()
                    } else {
                        stopCountdownTicks()
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            SleepSyncCoordinator.get(applicationContext).setVisible(sleepSurface, visible)
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
                    // Full canvas redraw at 10s; countdown seconds use a separate dirty-rect 1 Hz job.
                    delay(10000)
                }
            }
            syncCountdownTicks()
        }

        private fun stopRendering() {
            renderJob?.cancel()
            stopCountdownTicks()
        }

        private fun syncCountdownTicks() {
            if (!isVisible || !currentSettings.showBedtimeCountdown) {
                stopCountdownTicks()
                return
            }
            val bedtimeMillis = BedtimeNotificationManager.resolveBedtimeMillis(sleepLogs)
            if (!renderer.isBedtimeCountdownTicking(bedtimeMillis)) {
                stopCountdownTicks()
                return
            }
            if (countdownJob?.isActive == true) return
            countdownJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!isVisible || !currentSettings.showBedtimeCountdown) break
                    val millis = BedtimeNotificationManager.resolveBedtimeMillis(sleepLogs)
                    if (!renderer.isBedtimeCountdownTicking(millis)) break
                    drawCountdownDirty(millis)
                }
                countdownJob = null
            }
        }

        private fun stopCountdownTicks() {
            countdownJob?.cancel()
            countdownJob = null
        }

        private fun clockLayout(width: Int, height: Int, smallTopRight: Boolean): Triple<Float, Float, Float> {
            return if (smallTopRight) {
                val radius = min(width, height) / 6f * 0.9f
                val centerX = width - radius - 80f
                val centerY = radius + 150f
                Triple(centerX, centerY, radius)
            } else {
                val centerX = width / 2f
                val radius = min(width, height) / 2f - 150f
                val moonDiameter = max(12f, radius / 7f)
                val centerY = height / 2f - (1.75f * moonDiameter)
                Triple(centerX, centerY, radius)
            }
        }

        private fun drawCountdownDirty(bedtimeMillis: Long) {
            val holder = surfaceHolder
            val frame = holder.surfaceFrame
            if (frame.isEmpty) return
            val width = frame.width()
            val height = frame.height()
            val smallTopRight = currentSettings.smallTopRight
            val (centerX, centerY, radius) = clockLayout(width, height, smallTopRight)
            if (radius < 20f) return

            // Measure first so we can skip unsafe clears that would erase the dial.
            val prior = lastCountdownDirty
            val probeResult = renderer.measureBedtimeCountdown(
                width = width,
                height = height,
                bedtimeMillis = bedtimeMillis,
                smallTopRight = smallTopRight,
                clockCenterX = centerX,
                clockCenterY = centerY,
                clockRadius = radius
            )
            if (probeResult.intersectsClock || probeResult.isOverdue) return

            val clearRect = Rect(probeResult.contentDirty)
            if (prior != null) clearRect.union(prior)

            val canvas = holder.lockCanvas(clearRect) ?: return
            try {
                val result = renderer.drawBedtimeCountdown(
                    canvas = canvas,
                    width = width,
                    height = height,
                    bedtimeMillis = bedtimeMillis,
                    smallTopRight = smallTopRight,
                    clockCenterX = centerX,
                    clockCenterY = centerY,
                    clockRadius = radius,
                    clearRect = clearRect
                )
                if (!result.intersectsClock) {
                    lastCountdownDirty = Rect(result.contentDirty)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun startDataUpdates() {
            dataUpdateJob?.cancel()
            dataUpdateJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        updateCelestialData()
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
            
            val celestial = celestialManager.getCelestialPositions()
            sunRad = celestial.sunRad
            moonRad = celestial.moonRad
            moonPhaseValue = celestial.moonPhase
            sunElevation = celestial.sunElevation

            solarIrradiance = celestialManager.getSolarIrradiance()
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
                    canvas = canvas,
                    width = canvas.width,
                    height = canvas.height,
                    now = Calendar.getInstance(),
                    sunriseHour = sunriseHour,
                    sunsetHour = sunsetHour,
                    sleepLogs = sleepLogs,
                    sunRad = sunRad,
                    moonRad = moonRad,
                    moonPhaseValue = moonPhaseValue,
                    sunElevation = sunElevation,
                    solarIrradiance = solarIrradiance,
                    sleepDebt = sleepDebt,
                    bathyphaseHour = bathyphaseHour,
                    calendarEvents = calendarEvents,
                    showNumbers = currentSettings.showNumbers,
                    showSleep = currentSettings.showSleep,
                    sleepOpacity = sleepStatus.opacity(System.currentTimeMillis(), reconnectRequired),
                    sleepSavedAt = sleepStatus.lastSavedAt,
                    showSunMoon = currentSettings.showSunMoon,
                    showSleepDebtText = currentSettings.showSleepDebtText,
                    showEnergy = currentSettings.showEnergy,
                    showCalendar = currentSettings.showCalendar,
                    smallTopRight = currentSettings.smallTopRight,
                    showLifeCalendar = currentSettings.showLifeCalendar,
                    showTotalBedtime = currentSettings.showTotalBedtime,
                    showEnergyPct = currentSettings.showEnergyPct,
                    normalizeEnergy = currentSettings.normalizeEnergy,
                    includeNaps = modelSettings.includeNaps,
                    tauWake = modelSettings.tauWake,
                    tauSleep = modelSettings.tauSleep,
                    tauInertia = modelSettings.tauInertia,
                    debtFactor = modelSettings.debtFactor,
                    circadianOffset = modelSettings.circadianOffset,
                    useBathyphase = modelSettings.useBathyphase,
                    bedtimeGoal = modelSettings.bedtimeGoal,
                    showManualWake = currentSettings.showManualWake,
                    manualWakeTime = modelSettings.manualWakeTime,
                    showWakeSunriseInfo = currentSettings.showWakeSunriseInfo,
                    showTimezoneMap = currentSettings.showTimezoneMap,
                    userLatitude = celestialManager.latitude,
                    userLongitude = celestialManager.longitude,
                    showBathyphase = currentSettings.showBathyphase,
                    showAcrophase = currentSettings.showAcrophase,
                    showGrogginess = currentSettings.showGrogginess,
                    showWindDown = currentSettings.showWindDown,
                    showBedtimeCountdown = currentSettings.showBedtimeCountdown,
                    isPreview = isPreview,
                    previewIsLockScreen = previewLockScreen,
                    modelSettings = modelSettings
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
            // Seed dirty bounds after a full frame so the 1 Hz path can union correctly.
            lastCountdownDirty = renderer.lastCountdownDrawResult?.contentDirty?.let { Rect(it) }
            syncCountdownTicks()
        }

        override fun onDestroy() {
            SleepSyncCoordinator.get(applicationContext).setVisible(sleepSurface, false)
            scope.cancel()
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
