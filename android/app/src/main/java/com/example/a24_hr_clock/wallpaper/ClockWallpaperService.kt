package com.example.a24_hr_clock.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
private const val ZOOM_IDLE = 0.01f

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
        private lateinit var celestialManager: CelestialManager
        private lateinit var settingsManager: SettingsManager

        private var renderJob: Job? = null
        private var dataUpdateJob: Job? = null
        private var settingsJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private val refreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Refresh broadcast received")
                scope.launch {
                    try {
                        updateCelestialData()
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

        private var sunriseHour = 6.0
        private var sunsetHour = 18.0
        private var sunRad = 0.0
        private var moonRad = 0.0
        private var moonPhaseValue = 0.0
        private var sunElevation = 0.0
        private var sleepLogs = emptyList<SleepLogEntry>()
        private var sleepStatus = SleepSyncStatus()
        private var reconnectRequired = false
        private var sleepDebt = 0.0
        private var bathyphaseHour: Double? = null
        private var calendarEvents = emptyList<CalendarEvent>()
        private var solarIrradiance = 0

        private var currentSettings = ClockSettings()
        private var modelSettings = ModelSettings()
        private var plannedTasks = emptyList<PlannedTask>()
        private var taskDial: ClockDial? = null
        private var draggingTask: PlannedTask? = null
        private var touchedTask: PlannedTask? = null
        private var initialPointerMinute = 0.0
        private var touchX = 0f
        private var touchY = 0f
        private var touchMoved = false
        private val touchSlop by lazy { android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop }
        private val isLockedState = MutableStateFlow(false)
        private var previewLockScreen = false
        private var zoomSettled = true

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            fitbitManager = FitbitManager(applicationContext)
            settingsManager = SettingsManager(applicationContext)
            celestialManager = CelestialManager(40.7128, -74.0060)
            renderer.ensureMapBitmap(applicationContext.resources)

            updateLockState()
            startDataUpdates()
            observeAllData()
            observeStoredLocation()
            SharedCalendarFeed.ensureStarted(applicationContext)
            scope.launch {
                SharedCalendarFeed.eventsFlow.collect { events ->
                    calendarEvents = events
                    drawFrame()
                }
            }

            val filter = IntentFilter("com.example.a24_hr_clock.REFRESH_DATA")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(refreshReceiver, filter)
            }

            val lockFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(lockStateReceiver, lockFilter)
            notifyColorsChanged()
        }

        override fun onComputeColors(): android.app.WallpaperColors {
            val black = Color.valueOf(0f, 0f, 0f)
            return android.app.WallpaperColors(
                black,
                black,
                black,
                android.app.WallpaperColors.HINT_SUPPORTS_DARK_THEME
            )
        }

        override fun onZoomChanged(zoom: Float) {
            val settled = zoom <= ZOOM_IDLE
            if (settled == zoomSettled) return
            zoomSettled = settled
            if (isVisible && zoomSettled) startRendering() else stopRendering()
        }

        private fun updateLockState() {
            if (isPreview) {
                isLockedState.value = previewLockScreen
                return
            }
            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            isLockedState.value = km.isKeyguardLocked
        }

        override fun onTouchEvent(event: android.view.MotionEvent?) {
            super.onTouchEvent(event)
            if (event == null) return
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchX = event.x
                    touchY = event.y
                    touchMoved = false
                    draggingTask = null
                    touchedTask = if (currentSettings.showTasks) taskDial?.taskAt(event.x, event.y, plannedTasks) else null
                    initialPointerMinute = taskDial?.minuteAt(event.x, event.y) ?: 0.0
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(event.x - touchX, event.y - touchY) > touchSlop) touchMoved = true
                    val task = touchedTask
                    if (touchMoved && task != null && currentSettings.showTasks) {
                        val start = taskDial?.rotatedStart(task, initialPointerMinute, event.x, event.y) ?: task.startMinute
                        draggingTask = task.copy(startMinute = start)
                        drawFrame()
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val task = touchedTask
                    if (touchMoved && task != null && currentSettings.showTasks) {
                        val start = taskDial?.rotatedStart(task, initialPointerMinute, event.x, event.y) ?: task.startMinute
                        scope.launch { settingsManager.movePlannedTask(task.id, start) }
                    } else if (isPreview && !touchMoved) {
                        previewLockScreen = !previewLockScreen
                        updateLockState()
                    }
                    touchedTask = null
                    draggingTask = null
                    drawFrame()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    touchedTask = null
                    draggingTask = null
                    drawFrame()
                }
            }
        }

        private fun observeStoredLocation() {
            scope.launch {
                settingsManager.locationFlow.collect { location ->
                    if (location == null) return@collect
                    celestialManager = CelestialManager(location.first, location.second)
                    updateCelestialData()
                    drawFrame()
                }
            }
        }

        private fun observeAllData() {
            settingsJob?.cancel()
            settingsJob = scope.launch {
                launch {
                    settingsManager.plannedTasksFlow.collect {
                        plannedTasks = it
                        drawFrame()
                    }
                }
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
                    sleepLogs = snapshot.logs
                    sleepStatus = snapshot.sync
                    reconnectRequired = snapshot.reconnect
                    sleepDebt = snapshot.debt
                    bathyphaseHour = snapshot.bathy
                    drawFrame()
                    if (isVisible && zoomSettled) startRendering() else stopRendering()
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                updateLockState()
                if (!isPreview) DailyClearScheduler.launchCatchUp(applicationContext)
                if (zoomSettled) startRendering()
            } else {
                touchedTask = null
                draggingTask = null
                stopRendering()
            }
        }

        private fun canDraw(): Boolean = isVisible && zoomSettled

        private fun startRendering() {
            if (!canDraw()) return
            renderJob?.cancel()
            renderJob = scope.launch {
                while (isActive && canDraw()) {
                    drawFrame()
                    delay(10000)
                }
            }
        }

        private fun stopRendering() {
            renderJob?.cancel()
            renderJob = null
        }

        private fun startDataUpdates() {
            dataUpdateJob?.cancel()
            dataUpdateJob = scope.launch {
                while (isActive) {
                    try {
                        updateCelestialData()
                        drawFrame()
                    } catch (e: Exception) {
                        Log.e(TAG, "Data update loop error", e)
                    }
                    delay(600000)
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

        private fun drawFrame() {
            if (!canDraw()) return
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                val insets = clockInsets(applicationContext)
                taskDial = ClockDial.create(canvas.width, canvas.height, currentSettings.smallTopRight,
                    insets.topPx, insets.sidePx, insets.dialMarginPx)
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
                    showSleepStages = currentSettings.showSleepStages,
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
                    modelSettings = modelSettings,
                    topInsetPx = insets.topPx,
                    sideInsetPx = insets.sidePx,
                    dialMarginPx = insets.dialMarginPx,
                    plannedTasks = plannedTasks.map { task -> draggingTask?.takeIf { it.id == task.id } ?: task },
                    showTasks = currentSettings.showTasks
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onDestroy() {
            scope.cancel()
            try {
                unregisterReceiver(refreshReceiver)
            } catch (_: IllegalArgumentException) {
            }
            try {
                unregisterReceiver(lockStateReceiver)
            } catch (_: IllegalArgumentException) {
            }
            super.onDestroy()
            stopRendering()
            dataUpdateJob?.cancel()
            settingsJob?.cancel()
        }
    }
}
