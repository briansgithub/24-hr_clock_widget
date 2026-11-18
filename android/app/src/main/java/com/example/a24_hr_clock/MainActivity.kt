package com.example.a24_hr_clock

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.repeatOnLifecycle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.a24_hr_clock.logic.*
import com.example.a24_hr_clock.ui.*
import com.example.a24_hr_clock.wallpaper.ClockRenderer
import com.example.a24_hr_clock.wallpaper.ClockWallpaperService
import com.example.a24_hr_clock.wallpaper.clockInsets
import com.example.a24_hr_clock.ui.theme._24_hr_clockTheme
import java.util.*
import kotlin.math.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

enum class Screen {
    PREVIEW,
    PLAN,
    DISPLAY,
    ENERGY,
    SLEEP,
    EXERCISE,
    LOG_HISTORY,
    LOG_INPUT
}

class MainActivity : ComponentActivity() {
    private lateinit var fitbitManager: FitbitManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var exerciseRepository: ExerciseRepository
    private var initialAction by mutableStateOf<String?>(null)
    private var showHealthDisclosure by mutableStateOf(false)
    private val healthAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                finishHealthAuthorization(FitbitSession.get(this).googleAuth.client.getAuthorizationResultFromIntent(result.data!!))
            } catch (_: com.google.android.gms.common.api.ApiException) {
                Toast.makeText(this, "Google Health connection was not completed. Cached sleep remains visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
        }
        if (permissions[Manifest.permission.READ_CALENDAR] == true) {
            Toast.makeText(this, "Calendar permission granted", Toast.LENGTH_SHORT).show()
            context.sendBroadcast(Intent("com.example.a24_hr_clock.REFRESH_DATA"))
        }
    }

    private lateinit var context: android.content.Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context = this
        fitbitManager = FitbitManager(this)
        settingsManager = SettingsManager(this)
        exerciseRepository = ExerciseRepository(this, fitbitManager)
        
        checkPermissions()
        
        // Initialize Bedtime Notifications
        lifecycleScope.launch {
            val bedtimeManager = BedtimeNotificationManager(this@MainActivity)
            bedtimeManager.updateNotifications()
        }

        enableEdgeToEdge()
        setContent {
            _24_hr_clockTheme {
                if (showHealthDisclosure) AlertDialog(
                    onDismissRequest = { showHealthDisclosure = false },
                    title = { Text("Connect Google Health") },
                    text = { Text("The clock reads your sleep and heart measurements to draw sleep sections and calculate energy. Health records stay on this device; the push relay receives only account and device routing information. Your cached sleep stays visible while connecting.") },
                    confirmButton = { TextButton(onClick = { showHealthDisclosure = false; beginHealthAuthorization() }) { Text("Continue with Google") } },
                    dismissButton = { TextButton(onClick = { showHealthDisclosure = false }) { Text("Later") } }
                )
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        fitbitManager = fitbitManager,
                        settingsManager = settingsManager,
                        exerciseRepository = exerciseRepository,
                        initialAction = initialAction,
                        onClearAction = { initialAction = null },
                        modifier = Modifier.padding(innerPadding),
                        onLoginClick = { launchFitbitAuth() },
                        onLogoutClick = {
                            lifecycleScope.launch {
                                fitbitManager.logout()
                                Toast.makeText(this@MainActivity, "Logged out", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        val syncManager = SyncManager(this)
        syncManager.schedulePeriodicSync()
        syncManager.scheduleMaintenance()
        syncManager.scheduleEmpiricalEnergyWorker()
        syncManager.scheduleMissedDataCheckWorker()

        handleIntent(intent)
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        SleepSyncCoordinator.get(this).setVisible("activity", true)
        lifecycleScope.launch { PushRegistrationManager.get(this@MainActivity).register() }
    }

    override fun onPause() {
        SleepSyncCoordinator.get(this).setVisible("activity", false)
        super.onPause()
    }

    private fun launchFitbitAuth() {
        lifecycleScope.launch {
            FitbitSession.get(this@MainActivity).store.dismissReconnect()
            if (fitbitManager.store.credentials().provider == "google_health") beginHealthAuthorization()
            else showHealthDisclosure = true
        }
    }

    private fun beginHealthAuthorization() {
        lifecycleScope.launch {
            try {
                val result = FitbitSession.get(this@MainActivity).googleAuth.begin()
                if (result.hasResolution()) healthAuthorization.launch(androidx.activity.result.IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                else finishHealthAuthorization(result)
            } catch (_: FitbitRequestException) {
                Toast.makeText(this@MainActivity, "Google Health authorization is unavailable. Cached sleep remains visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishHealthAuthorization(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        lifecycleScope.launch {
            try {
                FitbitSession.get(this@MainActivity).googleAuth.complete(result)
                Toast.makeText(this@MainActivity, "Google Health connected. Sleep updated.", Toast.LENGTH_SHORT).show()
                SleepSyncCoordinator.get(this@MainActivity).request(history = true, immediate = true)
                PushRegistrationManager.get(this@MainActivity).register(force = true)
            } catch (e: FitbitRequestException) {
                android.util.Log.w("GoogleHealthAuth", "Connection verification failed: category=${e.category}")
                Toast.makeText(this@MainActivity, "Connection could not be verified (${e.category}). Cached sleep remains visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action")
        intent?.removeExtra("action")
        if (action == "reauth") {
            // Android can recreate the activity with its original notification Intent.
            // Persist consumption before opening the browser, including across process death.
            val requestId = intent.getStringExtra(AuthNotificationManager.RECONNECT_REQUEST_ID)
                ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: "legacy-reauth"
            intent.removeExtra(AuthNotificationManager.RECONNECT_REQUEST_ID)
            lifecycleScope.launch {
                if (fitbitManager.store.consumeReconnectRequest(requestId)) launchFitbitAuth()
            }
        } else if (action != null) initialAction = action
        val data = intent?.data ?: return
        intent.data = null // Consume both cold-start and foreground delivery exactly once.
        if (data.scheme == "fitbit24h" && data.host == "callback") lifecycleScope.launch {
            if (fitbitManager.handleAuthCallback(data)) {
                Toast.makeText(this@MainActivity, "Fitbit reconnected. Checking sleep...", Toast.LENGTH_SHORT).show()
                SleepSyncCoordinator.get(this@MainActivity).request(history = true, immediate = true)
                PushRegistrationManager.get(this@MainActivity).register(force = true)
            } else {
                Toast.makeText(this@MainActivity, "Fitbit connection was not completed. Cached sleep remains visible.", Toast.LENGTH_LONG).show()
            }
        }
    }

}

@Composable
fun MainScreen(
    fitbitManager: FitbitManager,
    settingsManager: SettingsManager,
    exerciseRepository: ExerciseRepository,
    initialAction: String? = null,
    onClearAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isLoggedIn by fitbitManager.loginStatusFlow.collectAsState(initial = false)
    val sleepSnapshot by fitbitManager.sleepSnapshotFlow.collectAsState(initial = SleepSnapshot(emptyList(), SleepSyncStatus(), FitbitCredentials()))
    val sleepLogs = sleepSnapshot.logs
    val pushStatus by PushRegistrationManager.get(context).statusFlow.collectAsState(initial = PushStatus())
    val reconnect = sleepSnapshot.auth.reconnectEpisode != null
    if (reconnect && sleepSnapshot.auth.dismissedEpisode != sleepSnapshot.auth.reconnectEpisode) {
        fun later() { scope.launch { FitbitSession.get(context).store.dismissReconnect() } }
        AlertDialog(
            onDismissRequest = { later() },
            title = { Text("Reconnect Google Health") },
            text = { Text("Health access needs your permission again. Your cached sleep remains visible while you reconnect.") },
            confirmButton = { TextButton(onClick = onLoginClick) { Text("Reconnect Google Health") } },
            dismissButton = { TextButton(onClick = { later() }) { Text("Later") } }
        )
    }
    val metrics by fitbitManager.metricsFlow.collectAsState(initial = Triple(null, 1.0, 9.75))
    
    val homeSettings by settingsManager.homeSettingsFlow.collectAsState(initial = ClockSettings())
    val lockSettings by settingsManager.lockSettingsFlow.collectAsState(initial = ClockSettings())
    val modelSettings by settingsManager.modelSettingsFlow.collectAsState(initial = ModelSettings())
    val calendarSettings by settingsManager.calendarSettingsFlow.collectAsState(initial = CalendarSettings())
    val plannedTasks by settingsManager.plannedTasksFlow.collectAsState(initial = emptyList())
    // Session-only undo/redo of plan edits; the version counter makes the buttons recompose.
    val planHistory = remember { PlanHistory() }
    var planHistoryVersion by remember { mutableIntStateOf(0) }
    fun editPlan(after: (List<PlannedTask>) -> List<PlannedTask>) {
        val before = plannedTasks
        val next = after(before)
        planHistory.record(before, next)
        planHistoryVersion++
        scope.launch { settingsManager.replacePlannedTasks(next) }
    }
    fun savePlanTask(task: PlannedTask) = editPlan { tasks ->
        if (tasks.any { it.id == task.id }) tasks.map { if (it.id == task.id) task else it } else tasks + task
    }
    fun movePlanTask(task: PlannedTask) = editPlan { tasks ->
        tasks.map { if (it.id == task.id) it.copy(startMinute = task.startMinute) else it }
    }
    fun stepPlan(undo: Boolean) {
        val restored = (if (undo) planHistory.undo(plannedTasks) else planHistory.redo(plannedTasks)) ?: return
        planHistoryVersion++
        scope.launch { settingsManager.replacePlannedTasks(restored) }
    }
    
    // Automatic cleanup: when "today" becomes "yesterday", if it was excluded, re-include it.
    val todayDate = java.time.LocalDate.now().toString()
    LaunchedEffect(todayDate, modelSettings.lastTodayDate) {
        if (modelSettings.lastTodayDate.isNotEmpty() && modelSettings.lastTodayDate != todayDate) {
            val newExcluded = modelSettings.excludedDates.filter { it != modelSettings.lastTodayDate }
            val newExplicit = modelSettings.explicitDates.filter { it != modelSettings.lastTodayDate }
            settingsManager.updateModelSettings(modelSettings.copy(
                excludedDates = newExcluded,
                explicitDates = newExplicit,
                lastTodayDate = todayDate
            ))
        } else if (modelSettings.lastTodayDate.isEmpty()) {
            settingsManager.updateModelSettings(modelSettings.copy(lastTodayDate = todayDate))
        }
    }

    // T-0 Auto-Inclusion Logic (Match Python behavior)
    LaunchedEffect(sleepLogs, modelSettings.explicitDates) {
        val todayStr = java.time.LocalDate.now().toString()
        if (!modelSettings.explicitDates.contains(todayStr)) {
            val hasTodayLog = sleepLogs.any { it.dateOfSleep == todayStr }
            val isExcluded = modelSettings.excludedDates.contains(todayStr)
            
            if (hasTodayLog && isExcluded) {
                settingsManager.updateModelSettings(modelSettings.copy(
                    excludedDates = modelSettings.excludedDates - todayStr
                ))
            } else if (!hasTodayLog && !isExcluded) {
                settingsManager.updateModelSettings(modelSettings.copy(
                    excludedDates = modelSettings.excludedDates + todayStr
                ))
            }
        }
    }

    // --- Data for Preview ---
    val locationManager = remember { LocationManager(context) }
    var sunTimes by remember { mutableStateOf(Pair(6.0, 18.0)) }
    var celestialPositions by remember { mutableStateOf(CelestialManager.SunMoonPosition(0.0, 0.0, 0.0, 0.0)) }
    var deviceLatitude by remember { mutableStateOf<Double?>(null) }
    var deviceLongitude by remember { mutableStateOf<Double?>(null) }
    var solarIrradiance by remember { mutableIntStateOf(255) }
    var calendarEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    val calendarManager = remember { CalendarManager(context) }

    // Initialization of Calendar Settings if needed
    LaunchedEffect(calendarSettings.initialized) {
        if (!calendarSettings.initialized && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            val allCals = calendarManager.getAllCalendars()
            val primaryIds = allCals.filter { it.isPrimary }.map { it.id }.toSet()
            settingsManager.updateCalendarSettings(calendarSettings.copy(
                enabledIds = primaryIds.ifEmpty { calendarSettings.enabledIds },
                initialized = true
            ))
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, isLoggedIn, calendarSettings.enabledIds) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                val loc = locationManager.getLastKnownLocation() ?: locationManager.getCurrentLocation()
                if (loc != null) {
                    deviceLatitude = loc.first
                    deviceLongitude = loc.second
                    settingsManager.saveLocation(loc.first, loc.second)
                    val cm = CelestialManager(loc.first, loc.second)
                    sunTimes = cm.getSunTimes()
                    celestialPositions = cm.getCelestialPositions()
                    solarIrradiance = cm.getSolarIrradiance()
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                    calendarEvents = calendarManager.getTodayEvents(calendarSettings.enabledIds)
                }
                delay(600000)
            }
        }
    }

    val sleepDebt = remember(sleepLogs, metrics, modelSettings) {
        val mappedLogs = sleepLogs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
        EnergyCalculator.computeSleepDebt(mappedLogs, metrics.third, modelSettings.includeNaps, modelSettings.excludedDates)
    }

    var currentScreen by remember { mutableStateOf(Screen.PREVIEW) }
    var previewIsLockScreen by remember { mutableStateOf(true) }

    LaunchedEffect(initialAction) {
        if (initialAction != null) {
            when (initialAction) {
                "log_energy" -> currentScreen = Screen.LOG_INPUT
                "log_history" -> currentScreen = Screen.LOG_HISTORY
            }
            onClearAction()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen == Screen.PREVIEW,
                    onClick = { currentScreen = Screen.PREVIEW },
                    label = { Text("Preview") },
                    icon = { Icon(Icons.Default.Visibility, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.PLAN,
                    onClick = { currentScreen = Screen.PLAN },
                    label = { Text("Plan") },
                    icon = { Icon(Icons.Default.EditCalendar, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.DISPLAY,
                    onClick = { currentScreen = Screen.DISPLAY },
                    label = { Text("Display") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.ENERGY,
                    onClick = { currentScreen = Screen.ENERGY },
                    label = { Text("Energy") },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.SLEEP,
                    onClick = { currentScreen = Screen.SLEEP },
                    label = { Text("Sleep") },
                    icon = { Icon(Icons.Default.List, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.EXERCISE,
                    onClick = { currentScreen = Screen.EXERCISE },
                    label = { Text("Exercise") },
                    icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                Screen.PREVIEW -> ClockPreviewScreen(
                    sleepStatus = sleepSnapshot.status,
                    reconnectRequired = reconnect,
                    settings = if (previewIsLockScreen) lockSettings else homeSettings,
                    modelSettings = modelSettings,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    sunTimes = sunTimes,
                    celestialPositions = celestialPositions,
                    solarIrradiance = solarIrradiance,
                    calendarEvents = calendarEvents,
                    plannedTasks = plannedTasks,
                    onMoveTask = { task -> movePlanTask(task) },
                    sleepDebt = sleepDebt,
                    userLatitude = deviceLatitude,
                    userLongitude = deviceLongitude,
                    title = if (previewIsLockScreen) "Lock Screen (Tap to toggle)" else "Home Screen (Tap to toggle)",
                    showSetWallpaper = true,
                    modifier = Modifier.clickable { previewIsLockScreen = !previewIsLockScreen }
                )
                Screen.PLAN -> PlanScreen(
                    tasks = plannedTasks,
                    onSave = { savePlanTask(it) },
                    onDelete = { id -> editPlan { tasks -> tasks.filterNot { it.id == id } } },
                    canUndo = planHistoryVersion >= 0 && planHistory.canUndo,
                    canRedo = planHistoryVersion >= 0 && planHistory.canRedo,
                    onUndo = { stepPlan(true) },
                    onRedo = { stepPlan(false) }
                ) { previewModifier ->
                    ClockPreviewScreen(
                        sleepStatus = sleepSnapshot.status,
                        reconnectRequired = reconnect,
                        settings = lockSettings.copy(smallTopRight = false, showTasks = true,
                            showTimezoneMap = false, showLifeCalendar = false,
                            showWakeSunriseInfo = false, showBedtimeCountdown = false),
                        modelSettings = modelSettings,
                        sleepLogs = sleepLogs,
                        metrics = metrics,
                        sunTimes = sunTimes,
                        celestialPositions = celestialPositions,
                        solarIrradiance = solarIrradiance,
                        calendarEvents = calendarEvents,
                        plannedTasks = plannedTasks,
                        onMoveTask = { task -> movePlanTask(task) },
                        sleepDebt = sleepDebt,
                        title = "",
                        showSetWallpaper = false,
                        compact = true,
                        modifier = previewModifier
                    )
                }
                Screen.DISPLAY -> DisplaySettingsScreen(
                    calendarSettings = calendarSettings,
                    homeSettings = homeSettings,
                    lockSettings = lockSettings,
                    onUpdateCalendarSettings = { scope.launch { settingsManager.updateCalendarSettings(it) } },
                    onUpdateHome = { scope.launch { settingsManager.updateHomeSettings(it) } },
                    onUpdateLock = { scope.launch { settingsManager.updateLockSettings(it) } },
                    onResetHome = { scope.launch { settingsManager.resetHomeSettings() } },
                    onResetLock = { scope.launch { settingsManager.resetLockSettings() } }
                )
                Screen.ENERGY -> ModelSettingsScreen(
                    modelSettings = modelSettings,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    sleepDebt = sleepDebt,
                    onUpdate = { scope.launch { settingsManager.updateModelSettings(it) } },
                    onReset = { scope.launch { settingsManager.resetModelSettings() } },
                    onNavigateToLogHistory = { currentScreen = Screen.LOG_HISTORY }
                )
                Screen.LOG_HISTORY -> EmpiricalLogHistoryScreen(
                    manager = remember { EmpiricalEnergyManager(context) },
                    modelSettings = modelSettings,
                    onUpdateGoogleDriveUrl = { url ->
                        scope.launch { settingsManager.updateModelSettings(modelSettings.copy(googleDriveUrl = url)) }
                    },
                    onUpdateLocalBackupUri = { uri ->
                        scope.launch { settingsManager.updateModelSettings(modelSettings.copy(localBackupUri = uri)) }
                    },
                    onBack = { currentScreen = Screen.ENERGY }
                )
                Screen.LOG_INPUT -> EnergyLogInputScreen(
                    onSave = { value ->
                        val manager = EmpiricalEnergyManager(context)
                        manager.logEnergy(System.currentTimeMillis(), value)
                        currentScreen = Screen.LOG_HISTORY
                    },
                    onCancel = { currentScreen = Screen.PREVIEW }
                )
                Screen.SLEEP -> SleepLogScreen(
                    syncStatus = sleepSnapshot.status,
                    reconnectRequired = reconnect,
                    pushStatus = pushStatus,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    modelSettings = modelSettings,
                    isLoggedIn = isLoggedIn,
                    onLoginClick = onLoginClick,
                    onLogoutClick = onLogoutClick,
                    onRefresh = {
                        scope.launch {
                            Toast.makeText(context, "Refreshing sleep data...", Toast.LENGTH_SHORT).show()
                            SleepSyncCoordinator.get(context).request(history = true, immediate = true).await()
                        }
                    },
                    onUpdateModel = { scope.launch { settingsManager.updateModelSettings(it) } }
                )
                Screen.EXERCISE -> ExerciseScreen(
                    repository = exerciseRepository,
                    modelSettings = modelSettings,
                    onUpdateModel = { scope.launch { settingsManager.updateModelSettings(it) } }
                )
            }
        }
    }
}

@Composable
fun ClockPreviewScreen(
    sleepStatus: SleepSyncStatus = SleepSyncStatus(),
    reconnectRequired: Boolean = false,
    settings: ClockSettings,
    modelSettings: ModelSettings,
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    sunTimes: Pair<Double, Double>,
    celestialPositions: CelestialManager.SunMoonPosition,
    solarIrradiance: Int,
    calendarEvents: List<CalendarEvent>,
    sleepDebt: Double,
    userLatitude: Double? = null,
    userLongitude: Double? = null,
    title: String = "Home Screen Preview",
    showSetWallpaper: Boolean = true,
    plannedTasks: List<PlannedTask> = emptyList(),
    onMoveTask: (PlannedTask) -> Unit = {},
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember { ClockRenderer() }
    var draggingTask by remember { mutableStateOf<PlannedTask?>(null) }
    val displayedTasks = plannedTasks.map { task -> draggingTask?.takeIf { it.id == task.id } ?: task }
    val insets = clockInsets(context)
    val dialMargin = if (compact) with(LocalDensity.current) { 16.dp.toPx() } else insets.dialMarginPx
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        renderer.ensureMapBitmap(context.resources)
        while(true) {
            delay(10000) // Match wallpaper cadence; countdown does not force 1s redraws
            tick = System.currentTimeMillis()
        }
    }

    val now = remember(tick) {
        Calendar.getInstance().apply { timeInMillis = tick }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds().taskWedgeDrag(
            tasks = plannedTasks,
            enabled = settings.showTasks,
            geometry = { size -> ClockDial.create(size.width, size.height, settings.smallTopRight,
                insets.topPx, insets.sidePx, dialMargin, centered = compact) },
            onPreview = { draggingTask = it },
            onCommit = onMoveTask
        )) {
            drawIntoCanvas { canvas ->
                renderer.draw(
                    canvas.nativeCanvas,
                    size.width.toInt(),
                    size.height.toInt(),
                    now,
                    sunTimes.first,
                    sunTimes.second,
                    sleepLogs,
                    celestialPositions.sunRad,
                    celestialPositions.moonRad,
                    celestialPositions.moonPhase,
                    celestialPositions.sunElevation,
                    solarIrradiance,
                    sleepDebt,
                    metrics.first,
                    calendarEvents,
                    showNumbers = settings.showNumbers,
                    showSleep = settings.showSleep,
                    sleepOpacity = sleepStatus.opacity(System.currentTimeMillis(), reconnectRequired),
                    sleepSavedAt = sleepStatus.lastSavedAt,
                    showSunMoon = settings.showSunMoon,
                    showSleepDebtText = settings.showSleepDebtText,
                    showEnergy = settings.showEnergy,
                    showCalendar = settings.showCalendar,
                    smallTopRight = settings.smallTopRight,
                    showLifeCalendar = settings.showLifeCalendar,
                    showTotalBedtime = settings.showTotalBedtime,
                    showEnergyPct = settings.showEnergyPct,
                    normalizeEnergy = settings.normalizeEnergy,
                    showBathyphase = settings.showBathyphase,
                    showAcrophase = settings.showAcrophase,
                    includeNaps = modelSettings.includeNaps,
                    tauWake = modelSettings.tauWake,
                    tauSleep = modelSettings.tauSleep,
                    tauInertia = modelSettings.tauInertia,
                    debtFactor = modelSettings.debtFactor,
                    circadianOffset = modelSettings.circadianOffset,
                    useBathyphase = modelSettings.useBathyphase,
                    bedtimeGoal = modelSettings.bedtimeGoal,
                    showManualWake = settings.showManualWake,
                    manualWakeTime = modelSettings.manualWakeTime,
                    showWakeSunriseInfo = settings.showWakeSunriseInfo,
                    showTimezoneMap = settings.showTimezoneMap,
                    userLatitude = userLatitude,
                    userLongitude = userLongitude,
                    showGrogginess = settings.showGrogginess,
                    showWindDown = settings.showWindDown,
                    showBedtimeCountdown = settings.showBedtimeCountdown,
                    isPreview = !compact,
                    previewIsLockScreen = title.contains("Lock Screen"),
                    modelSettings = modelSettings,
                    topInsetPx = insets.topPx,
                    sideInsetPx = insets.sidePx,
                    dialMarginPx = dialMargin,
                    plannedTasks = displayedTasks,
                    showTasks = settings.showTasks,
                    centerDial = compact
                )
            }
        }
        
        if (title.isNotEmpty()) Text(
            text = title,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            style = MaterialTheme.typography.labelLarge
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showSetWallpaper) {
                Button(
                    onClick = {
                        val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, 
                                android.content.ComponentName(context, com.example.a24_hr_clock.wallpaper.ClockWallpaperService::class.java))
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Set")
                }
            }
        }
    }
}

@Composable
fun DisplaySettingsScreen(
    calendarSettings: CalendarSettings,
    homeSettings: ClockSettings,
    lockSettings: ClockSettings,
    onUpdateCalendarSettings: (CalendarSettings) -> Unit,
    onUpdateHome: (ClockSettings) -> Unit,
    onUpdateLock: (ClockSettings) -> Unit,
    onResetHome: () -> Unit,
    onResetLock: () -> Unit
) {
    // 0 = Lock Screen (default), 1 = Home Screen
    var selectedTab by remember { mutableIntStateOf(0) }
    val currentSettings = if (selectedTab == 0) lockSettings else homeSettings
    val updateFunc: (ClockSettings) -> Unit = if (selectedTab == 0) onUpdateLock else onUpdateHome

    val context = LocalContext.current
    val calendarManager = remember { CalendarManager(context) }
    var allCalendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    val hasCalendarPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    var calendarsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(hasCalendarPermission) {
        if (hasCalendarPermission) {
            allCalendars = calendarManager.getAllCalendars()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Display Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Lock Screen") },
                icon = { Icon(Icons.Default.Lock, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Home Screen") },
                icon = { Icon(Icons.Default.Home, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = if (selectedTab == 0) onResetLock else onResetHome,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFBDBDBD),
                contentColor = Color(0xFF212121)
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Reset to Defaults", maxLines = 1, softWrap = false)
        }

        Spacer(modifier = Modifier.height(16.dp))
        DisplaySectionHeader("ELEMENTS")

        SettingToggle("Clock Face Numbers", currentSettings.showNumbers, { GlyphNumbers() }) {
            updateFunc(currentSettings.copy(showNumbers = it))
        }
        SettingToggle("Sun & Moon Icons", currentSettings.showSunMoon, { GlyphSunMoon() }) {
            updateFunc(currentSettings.copy(showSunMoon = it))
        }
        SettingToggle("Show Planned Tasks", currentSettings.showTasks, {
            Icon(Icons.Default.EditCalendar, contentDescription = null)
        }) {
            updateFunc(currentSettings.copy(showTasks = it))
        }
        SettingToggle("Small Clock in Top-Right", currentSettings.smallTopRight, { GlyphSmallTopRight() }) {
            updateFunc(currentSettings.copy(smallTopRight = it))
        }
        SettingToggle("Life Calendar Background", currentSettings.showLifeCalendar, { GlyphLifeCalendar() }) {
            updateFunc(currentSettings.copy(showLifeCalendar = it))
        }
        SettingToggle("Wake-up Offset & Timezone Info", currentSettings.showWakeSunriseInfo, { GlyphWakeSunriseInfo() }) {
            updateFunc(currentSettings.copy(showWakeSunriseInfo = it))
        }
        SettingToggle("Timezone World Map", currentSettings.showTimezoneMap, { GlyphTimezoneMap() }) {
            updateFunc(currentSettings.copy(showTimezoneMap = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        DisplaySectionHeader("SLEEP")

        SettingToggle("Show Sleep on Clock", currentSettings.showSleep, { GlyphSleepArc(showTotalBedtime = true) }) {
            updateFunc(currentSettings.copy(showSleep = it))
        }
        SettingToggle("Show Sleep Debt Text", currentSettings.showSleepDebtText, { GlyphSleepDebtText() }) {
            updateFunc(currentSettings.copy(showSleepDebtText = it))
        }
        SettingToggle(
            "Subtract Awake",
            !currentSettings.showTotalBedtime,
            { GlyphSubtractAwake() }
        ) {
            updateFunc(currentSettings.copy(showTotalBedtime = !it))
        }
        SettingToggle("Show Wake-up Indicator", currentSettings.showManualWake, { GlyphWakeIndicator() }) {
            updateFunc(currentSettings.copy(showManualWake = it))
        }
        SettingToggle("Bathyphase Indicator", currentSettings.showBathyphase, { GlyphBathyphase() }) {
            updateFunc(currentSettings.copy(showBathyphase = it))
        }
        SettingToggle("Acrophase Indicator", currentSettings.showAcrophase, { GlyphAcrophase() }) {
            updateFunc(currentSettings.copy(showAcrophase = it))
        }
        SettingToggle("Grogginess Wedge", currentSettings.showGrogginess, { GlyphGrogginess() }) {
            updateFunc(currentSettings.copy(showGrogginess = it))
        }
        SettingToggle("Wind-down Wedge", currentSettings.showWindDown, { GlyphWindDown() }) {
            updateFunc(currentSettings.copy(showWindDown = it))
        }
        SettingToggle("Bedtime Countdown", currentSettings.showBedtimeCountdown, { GlyphBedtimeCountdown() }) {
            updateFunc(currentSettings.copy(showBedtimeCountdown = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        DisplaySectionHeader("ENERGY")

        SettingToggle("Show Energy Curve", currentSettings.showEnergy, { GlyphEnergyCurve() }) {
            updateFunc(currentSettings.copy(showEnergy = it))
        }
        SettingToggle("Show Energy %", currentSettings.showEnergyPct, { GlyphEnergyPct() }) {
            updateFunc(currentSettings.copy(showEnergyPct = it))
        }
        SettingToggle("Normalize Energy", currentSettings.normalizeEnergy, { GlyphNormalizeEnergy() }) {
            updateFunc(currentSettings.copy(normalizeEnergy = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        DisplaySectionHeader("CALENDAR")

        SettingToggle("Show Calendar Events", currentSettings.showCalendar, { GlyphCalendarEvents() }) {
            updateFunc(currentSettings.copy(showCalendar = it))
        }

        if (currentSettings.showCalendar) {
            val selectedCount = allCalendars.count { calendarSettings.enabledIds.contains(it.id) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { calendarsExpanded = !calendarsExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Calendars · $selectedCount selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (calendarsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (calendarsExpanded) "Collapse calendars" else "Expand calendars"
                )
            }

            if (calendarsExpanded) {
                if (allCalendars.isEmpty() && hasCalendarPermission) {
                    Text(
                        "No calendars found or still loading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                allCalendars.forEach { cal ->
                    val checked = calendarSettings.enabledIds.contains(cal.id)
                    fun toggleCalendar(enabled: Boolean) {
                        val newIds = if (enabled) {
                            calendarSettings.enabledIds + cal.id
                        } else {
                            calendarSettings.enabledIds - cal.id
                        }
                        onUpdateCalendarSettings(calendarSettings.copy(enabledIds = newIds, initialized = true))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggleCalendar(!checked) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(cal.color), shape = MaterialTheme.shapes.small)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = cal.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = cal.account,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { toggleCalendar(it) },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (!hasCalendarPermission) {
            Button(
                onClick = {
                    (context as? MainActivity)?.requestPermissionLauncher?.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Authorize Google Calendar")
            }
        } else {
            Text(
                text = "Status: Authorized (System Calendar)",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.sendBroadcast(Intent("com.example.a24_hr_clock.REFRESH_DATA"))
                    Toast.makeText(context, "Refreshing Calendar...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("Sync Calendar Now")
            }
        }
    }
}

@Composable
private fun DisplaySectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

private fun parseEnergySleepDateTime(value: String): java.time.LocalDateTime? =
    runCatching {
        java.time.LocalDateTime.parse(value.removeSuffix("Z"))
    }.getOrNull() ?: runCatching {
        java.time.OffsetDateTime.parse(value).toLocalDateTime()
    }.getOrNull()

private fun energyTabTimings(
    sleepLogs: List<SleepLogEntry>,
    bathyphaseHour: Double?,
    sleepDebt: Double,
    modelSettings: ModelSettings
): Pair<Double?, Double?> {
    val mainSleep = sleepLogs.find { it.isMainSleep }
    val sleepContext = EnergyCalculator.buildSleepModelContext(
        sleepLogs,
        modelSettings.includeNaps
    )
    if (!sleepContext.available) return bathyphaseHour to null
    val mainWake = mainSleep?.let { parseEnergySleepDateTime(it.endTime) }
        ?: return bathyphaseHour to null
    val wakeHour = mainWake.hour + mainWake.minute / 60.0 + mainWake.second / 3600.0
    val sleepDuration = sleepContext.mainSleepDuration ?: return bathyphaseHour to null
    val sleepEfficiency = mainSleep.let {
        if (it.timeInBed > 0) it.minutesAsleep.toDouble() / it.timeInBed else 0.92
    }

    var peakEnergy = -Double.MAX_VALUE
    var acrophaseHour: Double? = null
    for (i in 0..144) {
        val hour = (i.toDouble() / 144) * 24.0
        val energy = EnergyCalculator.predictEnergyFromSettings(
            hClock = hour,
            wakeHour = wakeHour,
            sleepDebtHours = sleepDebt,
            sleepDuration = sleepDuration,
            bathyphaseHour = bathyphaseHour,
            settings = modelSettings,
            useBathyphase = modelSettings.useBathyphase,
            sleepEfficiency = sleepEfficiency,
            sleepContext = sleepContext
        ).value ?: continue
        if (energy > peakEnergy) {
            peakEnergy = energy
            acrophaseHour = hour
        }
    }
    return bathyphaseHour to acrophaseHour
}

private fun formatEnergyClockHour(hour: Double?): String {
    if (hour == null) return "--:-- --"
    val normalizedHour = hour.mod(24.0)
    val wholeHour = normalizedHour.toInt()
    val minute = ((normalizedHour - wholeHour) * 60).toInt()
    val amPm = if (wholeHour < 12) "AM" else "PM"
    val displayHour = wholeHour % 12
    return String.format("%d:%02d %s", if (displayHour == 0) 12 else displayHour, minute, amPm)
}

@Composable
fun ModelSettingsScreen(
    modelSettings: ModelSettings,
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    sleepDebt: Double,
    onUpdate: (ModelSettings) -> Unit,
    onReset: () -> Unit,
    onNavigateToLogHistory: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val timings = remember(sleepLogs, metrics, sleepDebt, modelSettings) {
            energyTabTimings(sleepLogs, metrics.first, sleepDebt, modelSettings)
        }
        Text(
            text = "Current metrics",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Bathyphase", modifier = Modifier.weight(1f))
            Text(formatEnergyClockHour(timings.first), fontWeight = FontWeight.Bold)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Predicted acrophase", modifier = Modifier.weight(1f))
            Text(formatEnergyClockHour(timings.second), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))

        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset Model Defaults")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onNavigateToLogHistory,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Empirical Alertness Logs")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Advanced Energy Model", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        PermissionsChecklist()
        Spacer(modifier = Modifier.height(24.dp))

        SettingSlider(
            label = "Bedtime Goal",
            value = modelSettings.bedtimeGoal,
            range = 6.0f..12.0f,
            unit = "h",
            onValueChange = { onUpdate(modelSettings.copy(bedtimeGoal = it.toDouble())) }
        )
        
        SettingSlider(
            label = "Circadian Offset",
            value = modelSettings.circadianOffset,
            range = 6.0f..16.0f,
            unit = "h",
            onValueChange = { onUpdate(modelSettings.copy(circadianOffset = it.toDouble())) }
        )

        SettingToggle("Use Bathyphase HR", modelSettings.useBathyphase) {
            onUpdate(modelSettings.copy(useBathyphase = it))
        }
        SettingToggle("Include Naps", modelSettings.includeNaps) {
            onUpdate(modelSettings.copy(includeNaps = it))
        }

        val parts = modelSettings.manualWakeTime.split(":")
        val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        Button(
            onClick = {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val timeString = String.format("%02d:%02d", hour, minute)
                        onUpdate(modelSettings.copy(manualWakeTime = timeString))
                    },
                    initialHour,
                    initialMinute,
                    true
                ).show()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Set Manual Wake Time (Current: ${modelSettings.manualWakeTime})")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "MODEL PARAMETERS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

        SettingSlider(
            label = "Homeostatic Tau (Awake)",
            value = modelSettings.tauWake,
            range = 10.0f..30.0f,
            unit = "h",
            onValueChange = { onUpdate(modelSettings.copy(tauWake = it.toDouble())) }
        )
        SettingSlider(
            label = "Homeostatic Tau (Sleep)",
            value = modelSettings.tauSleep,
            range = 2.0f..8.0f,
            unit = "h",
            onValueChange = { onUpdate(modelSettings.copy(tauSleep = it.toDouble())) }
        )
        SettingSlider(
            label = "Sleep Inertia",
            value = modelSettings.tauInertia,
            range = 0.1f..4.0f,
            unit = "h",
            onValueChange = { onUpdate(modelSettings.copy(tauInertia = it.toDouble())) }
        )
        SettingSlider(
            label = "Debt Sensitivity",
            value = modelSettings.debtFactor,
            range = 0.0f..3.0f,
            unit = "",
            onValueChange = { onUpdate(modelSettings.copy(debtFactor = it.toDouble())) }
        )
    }
}

@Composable
fun SleepLogScreen(
    syncStatus: SleepSyncStatus,
    reconnectRequired: Boolean,
    pushStatus: PushStatus,
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    modelSettings: ModelSettings,
    isLoggedIn: Boolean,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateModel: (ModelSettings) -> Unit
) {
    val healthContext = LocalContext.current
    val healthAuth by FitbitSession.get(healthContext).store.credentialsFlow.collectAsState(initial = FitbitCredentials())
    val providerName = if (healthAuth.provider == "google_health") "Google Health" else "Fitbit"
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "$providerName Connection", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Status: " + when {
                reconnectRequired -> "Reconnect Google Health"
                !isLoggedIn -> "Not connected"
                syncStatus.phase == SleepSyncPhase.CHECKING -> "Checking sleep..."
                syncStatus.phase == SleepSyncPhase.PROCESSING -> "$providerName is still processing sleep"
                syncStatus.error != null -> when (syncStatus.error) {
                    SyncError.NETWORK -> "Unable to reach $providerName"
                    SyncError.RATE_LIMIT -> "$providerName has asked us to wait"
                    SyncError.SERVER -> "$providerName is temporarily unavailable"
                    SyncError.DECODING -> "$providerName returned an unreadable response"
                    SyncError.CONFIGURATION -> "$providerName connection settings need attention"
                    else -> "Reconnect Google Health"
                }
                syncStatus.lastVerifiedAt > 0 -> "Sleep verified"
                else -> "Cached sleep has not been verified"
            },
            color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )

        fun stamp(time: Long) = if (time == 0L) "Never" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time))
        Text("Last checked: ${stamp(syncStatus.lastAttemptAt)}")
        Text("Last successful check: ${stamp(syncStatus.lastVerifiedAt)}")
        Text("Latest sleep: ${sleepLogs.maxByOrNull { it.endTime }?.let { "${it.dateOfSleep}, ${it.startTime.substringAfter('T').take(5)} to ${it.endTime.substringAfter('T').take(5)}" } ?: "None"}")
        Text("Sleep records last changed: ${stamp(syncStatus.lastDataChangedAt)}")
        Text("Push delivery: ${pushStatus.message}")
        Text("Last push received on this device: ${stamp(pushStatus.receivedAt)}")
        val context = LocalContext.current
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Text("Notifications are disabled. You can reconnect here at any time.")
            TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
        }

        if (isLoggedIn) {
            val (bathy, eff, _) = metrics
            Text(
                text = "Overall Efficiency: ${String.format("%.1f", eff * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            val bathyStr = if (bathy != null) {
                val h = bathy.toInt()
                val m = ((bathy - h) * 60).toInt()
                val amPm = if (h < 12) "AM" else "PM"
                val hDisp = if (h % 12 == 0) 12 else h % 12
                String.format("%d:%02d %s", hDisp, m, amPm)
            } else "--:--"
            Text(
                text = "Bathyphase: $bathyStr",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onLoginClick, modifier = Modifier.weight(1f)) {
                Text(if (healthAuth.provider == "google_health") "Reconnect Google Health" else "Connect Google Health")
            }
            if (isLoggedIn) {
                OutlinedButton(onClick = onLogoutClick) {
                    Text("Logout")
                }
            }
        }

        if (isLoggedIn) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("API Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(text = "Sleep Log (Last 14 Days)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        SettingToggle("Include Naps in Debt Calculation", modelSettings.includeNaps) {
            onUpdateModel(modelSettings.copy(includeNaps = it))
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            val (_, _, sleepNeed) = metrics
            val dailyLogs = sleepLogs.groupBy { it.dateOfSleep }
            val today = java.time.LocalDate.now()
            val displayDates = (0 until 15).map { today.minusDays(it.toLong()).toString() }

            var totalRaw = 0.0
            var totalWtd = 0.0
            val durations = mutableListOf<Double>()
            val efficiencies = mutableListOf<Double>()
            val startHours = mutableListOf<Double>()
            val endHours = mutableListOf<Double>()

            // Header
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Inc", modifier = Modifier.weight(0.3f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Day", modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Date", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Start", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("End", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Dur", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Eff", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Raw", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Wtd", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

            displayDates.forEachIndexed { i, date ->
                val logsForDay = dailyLogs[date] ?: emptyList()
                val isExcluded = modelSettings.excludedDates.contains(date)
                val rowColor = if (isExcluded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurfaceVariant
                
                val dateObj = try { java.time.LocalDate.parse(date) } catch (e: Exception) { null }
                val dayStr = dateObj?.format(java.time.format.DateTimeFormatter.ofPattern("E")) ?: ""
                val dateStr = dateObj?.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) ?: date.takeLast(5)

                if (logsForDay.isEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = !isExcluded,
                            onCheckedChange = { checked ->
                                val newExcluded = if (checked) modelSettings.excludedDates.filter { it != date } else modelSettings.excludedDates + date
                                val newExplicit = if (date !in modelSettings.explicitDates) modelSettings.explicitDates + date else modelSettings.explicitDates
                                onUpdateModel(modelSettings.copy(excludedDates = newExcluded, explicitDates = newExplicit))
                            },
                            modifier = Modifier.weight(0.3f).scale(0.7f)
                        )
                        Text(dayStr, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                        Text(dateStr, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                        Text("—", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("—", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("0.0h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("—", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        
                        val debt = if (!isExcluded) sleepNeed else 0.0
                        if (!isExcluded) { 
                            val weightedDebt = debt * Math.pow(0.9, i.toDouble())
                            totalRaw += debt
                            totalWtd += weightedDebt
                            durations.add(0.0) 
                            
                            val debtColor = Color(0xFFFF6B6B)
                            Text(text = String.format("%+.1f", debt), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = debtColor.copy(alpha = 0.5f), textAlign = TextAlign.End)
                            Text(text = String.format("%+.1f", weightedDebt), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = debtColor, textAlign = TextAlign.End)
                        } else {
                            Text(text = "—", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = rowColor.copy(alpha = 0.5f), textAlign = TextAlign.End)
                            Text(text = "—", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = rowColor, textAlign = TextAlign.End)
                        }
                    }
                } else {
                    val totalDailyAsleep = if (modelSettings.includeNaps) logsForDay.sumOf { it.minutesAsleep / 60.0 } else logsForDay.filter { it.isMainSleep }.sumOf { it.minutesAsleep / 60.0 }
                    val totalDailyBed = if (modelSettings.includeNaps) logsForDay.sumOf { it.timeInBed } else logsForDay.filter { it.isMainSleep }.sumOf { it.timeInBed }
                    val dailyEff = if (totalDailyBed > 0) (totalDailyAsleep * 60.0 / totalDailyBed) else 0.0
                    
                    if (!isExcluded) {
                        durations.add(totalDailyAsleep)
                        
                        // Mean of session ratios, assuming naps are 100%
                        logsForDay.forEach { log ->
                            if (modelSettings.includeNaps || log.isMainSleep) {
                                val sessionEff = if (!log.isMainSleep) 1.0 
                                    else if (log.timeInBed > 0) log.minutesAsleep.toDouble() / log.timeInBed 
                                    else 0.92
                                efficiencies.add(sessionEff * 100.0)
                            }
                        }

                        val debt = sleepNeed - totalDailyAsleep
                        totalRaw += debt
                        totalWtd += debt * Math.pow(0.9, i.toDouble())
                        
                        // For AVG start/end, use main sleep
                        val mainSleep = logsForDay.find { it.isMainSleep } ?: logsForDay.first()
                        try {
                            val st = java.time.LocalDateTime.parse(mainSleep.startTime.replace("Z", ""))
                            startHours.add(st.hour + st.minute / 60.0)
                            val en = java.time.LocalDateTime.parse(mainSleep.endTime.replace("Z", ""))
                            endHours.add(en.hour + en.minute / 60.0)
                        } catch (e: Exception) {}
                    }

                    logsForDay.sortedBy { it.startTime }.forEachIndexed { sessionIdx, log ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (sessionIdx == 0) {
                                Checkbox(
                                    checked = !isExcluded,
                                    onCheckedChange = { checked ->
                                        val newExcluded = if (checked) modelSettings.excludedDates.filter { it != date } else modelSettings.excludedDates + date
                                        val newExplicit = if (date !in modelSettings.explicitDates) modelSettings.explicitDates + date else modelSettings.explicitDates
                                        onUpdateModel(modelSettings.copy(excludedDates = newExcluded, explicitDates = newExplicit))
                                    },
                                    modifier = Modifier.weight(0.3f).scale(0.7f)
                                )
                                Text(dayStr, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                                Text(dateStr, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                            } else {
                                Spacer(modifier = Modifier.weight(1.3f))
                            }

                            val startFmt = try { java.time.LocalDateTime.parse(log.startTime.replace("Z", "")).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")).replace("AM", "a").replace("PM", "p") } catch (e: Exception) { log.startTime.take(5) }
                            val endFmt = try { java.time.LocalDateTime.parse(log.endTime.replace("Z", "")).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")).replace("AM", "a").replace("PM", "p") } catch (e: Exception) { log.endTime.take(5) }
                            
                            Text(startFmt, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            Text(endFmt, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            Text("${String.format("%.1f", log.minutesAsleep/60.0)}h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            
                            val sessEff = if (log.timeInBed > 0) (log.minutesAsleep.toDouble() / log.timeInBed * 100).toInt() else 0
                            Text("$sessEff%", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)

                            if (sessionIdx == 0) {
                                val debt = sleepNeed - totalDailyAsleep
                                val weightedDebt = if (!isExcluded) debt * Math.pow(0.9, i.toDouble()) else 0.0
                                val debtColor = if (isExcluded) rowColor else if (debt > 0.1) Color(0xFFFF6B6B) else if (debt < -0.1) Color(0xFF6BFF6B) else MaterialTheme.colorScheme.onSurface
                                Text(text = if (isExcluded) "—" else String.format("%+.1f", debt), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = debtColor.copy(alpha = 0.5f), textAlign = TextAlign.End)
                                Text(text = if (isExcluded) "—" else String.format("%+.1f", weightedDebt), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = debtColor, textAlign = TextAlign.End)
                            } else {
                                Spacer(modifier = Modifier.weight(1.2f))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AVG", modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF00FFCC))
                
                val avgStart = EnergyCalculator.circAvg(startHours)
                val avgEnd = EnergyCalculator.circAvg(endHours)
                
                val avgStartStr = avgStart?.let { 
                    val h = it.toInt(); val m = ((it % 1.0) * 60).toInt()
                    String.format("%d:%02d%s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "a" else "p")
                } ?: "—"
                val avgEndStr = avgEnd?.let { 
                    val h = it.toInt(); val m = ((it % 1.0) * 60).toInt()
                    String.format("%d:%02d%s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "a" else "p")
                } ?: "—"
                
                Text(avgStartStr, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = Color.White)
                Text(avgEndStr, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = Color.White)
                
                val avgDur = if (durations.isNotEmpty()) durations.average() else 0.0
                val avgEff = if (efficiencies.isNotEmpty()) efficiencies.average() else 0.0
                Text(text = "${String.format("%.1f", avgDur)}h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = Color.White)
                Text(text = "${avgEff.toInt()}%", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = Color.White)
                
                val rawColor = if (totalRaw > 0.1) Color(0xFFFF6B6B) else if (totalRaw < -0.1) Color(0xFF6BFF6B) else Color(0xFF00FFCC)
                val wtdColor = if (totalWtd > 0.1) Color(0xFFFF6B6B) else if (totalWtd < -0.1) Color(0xFF6BFF6B) else Color(0xFF00FFCC)
                
                Text(text = String.format("%+.1f", totalRaw), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = rawColor.copy(alpha = 0.5f))
                Text(text = String.format("%+.1f", totalWtd), modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = wtdColor)
            }
        }
    }
}

@Composable
fun SettingToggle(
    label: String,
    checked: Boolean,
    leading: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    leading()
                }
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(text = label)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "${String.format("%.1f", value)}$unit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun PermissionsChecklist() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    refreshTrigger.let { }

    val locationOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val calendarOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    val batteryOk = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    val hibernationDisabled = context.packageManager.isAutoRevokeWhitelisted

    val allOk = locationOk && calendarOk && batteryOk && hibernationDisabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium)
            .padding(16.dp)
    ) {
        Text(
            text = if (allOk) "System Requirements (All Met)" else "System Requirements (Action Required)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (allOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))

        ChecklistItem(
            title = "1. Location: Allowed",
            isOk = locationOk,
            onClick = {
                (context as? MainActivity)?.requestPermissionLauncher?.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        )
        ChecklistItem(
            title = "2. Calendar: Allowed",
            isOk = calendarOk,
            onClick = {
                if (!calendarOk) {
                    (context as? MainActivity)?.requestPermissionLauncher?.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
            }
        )
        ChecklistItem(
            title = "3. Battery: 'Unrestricted'",
            isOk = batteryOk,
            onClick = {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            }
        )
        ChecklistItem(
            title = "4. Pause app if unused: 'Off'",
            isOk = hibernationDisabled,
            onClick = {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        )
    }
}

@Composable
fun ChecklistItem(title: String, isOk: Boolean, actionText: String = "Fix", onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isOk) "✅" else "⚠️",
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isOk) {
            Button(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(actionText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

