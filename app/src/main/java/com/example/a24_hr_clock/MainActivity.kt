package com.example.a24_hr_clock

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.a24_hr_clock.wallpaper.ClockRenderer
import com.example.a24_hr_clock.wallpaper.ClockWallpaperService
import com.example.a24_hr_clock.ui.theme._24_hr_clockTheme
import java.util.*
import kotlin.math.*

enum class Screen {
    PREVIEW,
    CONNECTIVITY,
    DISPLAY,
    MODEL,
    LOG
}

class MainActivity : ComponentActivity() {
    private lateinit var fitbitManager: FitbitManager
    private lateinit var settingsManager: SettingsManager

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
        
        checkPermissions()
        
        enableEdgeToEdge()
        setContent {
            _24_hr_clockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        fitbitManager = fitbitManager,
                        settingsManager = settingsManager,
                        modifier = Modifier.padding(innerPadding),
                        onLoginClick = { 
                            val intent = CustomTabsIntent.Builder().build()
                            intent.launchUrl(this, Uri.parse(fitbitManager.getAuthUrl()))
                        },
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

        handleIntent(intent)
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            hasFineLocation
        }

        if (!hasBackgroundLocation || !hasFineLocation) {
            Toast.makeText(this, "Please select 'Allow all the time' for Location", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent("android.intent.action.MANAGE_APP_PERMISSION")
                intent.putExtra("android.intent.extra.PERMISSION_GROUP_NAME", "android.permission-group.LOCATION")
                intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.toString().startsWith("fitbit24h://callback")) {
            val code = data.getQueryParameter("code")
            if (code != null) {
                lifecycleScope.launch {
                    val success = fitbitManager.handleAuthCode(code)
                    if (success) {
                        Toast.makeText(this@MainActivity, "Fitbit Login Successful! Syncing...", Toast.LENGTH_SHORT).show()
                        fitbitManager.refreshMetrics()
                        Toast.makeText(this@MainActivity, "Sync Complete", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Fitbit Login Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    fitbitManager: FitbitManager,
    settingsManager: SettingsManager,
    modifier: Modifier = Modifier,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isLoggedIn by fitbitManager.loginStatusFlow.collectAsState(initial = false)
    val sleepLogs by fitbitManager.sleepLogsFlow.collectAsState(initial = emptyList())
    val metrics by fitbitManager.metricsFlow.collectAsState(initial = Triple(null, 1.0, 9.75))
    
    val homeSettings by settingsManager.homeSettingsFlow.collectAsState(initial = ClockSettings())
    val lockSettings by settingsManager.lockSettingsFlow.collectAsState(initial = ClockSettings())
    val modelSettings by settingsManager.modelSettingsFlow.collectAsState(initial = ModelSettings())
    
    // Automatic cleanup: when "today" becomes "yesterday", if it was excluded, re-include it.
    val todayDate = java.time.LocalDate.now().toString()
    LaunchedEffect(todayDate, modelSettings.lastTodayDate) {
        if (modelSettings.lastTodayDate.isNotEmpty() && modelSettings.lastTodayDate != todayDate) {
            val newExcluded = modelSettings.excludedDates.filter { it != modelSettings.lastTodayDate }
            settingsManager.updateModelSettings(modelSettings.copy(
                excludedDates = newExcluded,
                lastTodayDate = todayDate
            ))
        } else if (modelSettings.lastTodayDate.isEmpty()) {
            settingsManager.updateModelSettings(modelSettings.copy(lastTodayDate = todayDate))
        }
    }

    // --- Data for Preview ---
    val locationManager = remember { LocationManager(context) }
    var sunTimes by remember { mutableStateOf(Pair(6.0, 18.0)) }
    var celestialPositions by remember { mutableStateOf(Triple(0.0, 0.0, 0.0)) }
    var solarIrradiance by remember { mutableIntStateOf(255) }
    var calendarEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    val calendarManager = remember { CalendarManager(context) }

    LaunchedEffect(isLoggedIn) {
        val loc = locationManager.getLastKnownLocation() ?: locationManager.getCurrentLocation()
        if (loc != null) {
            val cm = CelestialManager(loc.first, loc.second)
            sunTimes = cm.getSunTimes()
            celestialPositions = cm.getCelestialPositions()
            solarIrradiance = cm.getSolarIrradiance()
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            calendarEvents = calendarManager.getTodayEvents()
        }
    }

    val sleepDebt = remember(sleepLogs, metrics, modelSettings) {
        val mappedLogs = sleepLogs.map { SleepLog(it.dateOfSleep, it.minutesAsleep, it.isMainSleep, it.timeInBed) }
        EnergyCalculator.computeSleepDebt(mappedLogs, metrics.third, modelSettings.includeNaps, modelSettings.excludedDates)
    }

    var currentScreen by remember { mutableStateOf(Screen.PREVIEW) }

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
                    selected = currentScreen == Screen.CONNECTIVITY,
                    onClick = { currentScreen = Screen.CONNECTIVITY },
                    label = { Text("Connect") },
                    icon = { Icon(Icons.Default.Link, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.DISPLAY,
                    onClick = { currentScreen = Screen.DISPLAY },
                    label = { Text("Display") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.MODEL,
                    onClick = { currentScreen = Screen.MODEL },
                    label = { Text("Model") },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.LOG,
                    onClick = { currentScreen = Screen.LOG },
                    label = { Text("Log") },
                    icon = { Icon(Icons.Default.List, contentDescription = null) }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                Screen.PREVIEW -> ClockPreviewScreen(
                    settings = homeSettings,
                    modelSettings = modelSettings,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    sunTimes = sunTimes,
                    celestialPositions = celestialPositions,
                    solarIrradiance = solarIrradiance,
                    calendarEvents = calendarEvents,
                    sleepDebt = sleepDebt
                )
                Screen.CONNECTIVITY -> ConnectivityScreen(
                    isLoggedIn = isLoggedIn,
                    metrics = metrics,
                    onLoginClick = onLoginClick,
                    onLogoutClick = onLogoutClick,
                    onRefresh = {
                        scope.launch {
                            Toast.makeText(context, "Refreshing Fitbit data...", Toast.LENGTH_SHORT).show()
                            context.sendBroadcast(Intent("com.example.a24_hr_clock.REFRESH_DATA"))
                            fitbitManager.refreshMetrics()
                        }
                    }
                )
                Screen.DISPLAY -> DisplaySettingsScreen(
                    homeSettings = homeSettings,
                    lockSettings = lockSettings,
                    modelSettings = modelSettings,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    sunTimes = sunTimes,
                    celestialPositions = celestialPositions,
                    solarIrradiance = solarIrradiance,
                    calendarEvents = calendarEvents,
                    sleepDebt = sleepDebt,
                    onUpdateHome = { scope.launch { settingsManager.updateHomeSettings(it) } },
                    onUpdateLock = { scope.launch { settingsManager.updateLockSettings(it) } },
                    onResetHome = { scope.launch { settingsManager.resetHomeSettings() } },
                    onResetLock = { scope.launch { settingsManager.resetLockSettings() } }
                )
                Screen.MODEL -> ModelSettingsScreen(
                    modelSettings = modelSettings,
                    onUpdate = { scope.launch { settingsManager.updateModelSettings(it) } },
                    onReset = { scope.launch { settingsManager.resetModelSettings() } }
                )
                Screen.LOG -> SleepLogScreen(
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    modelSettings = modelSettings,
                    onUpdateModel = { scope.launch { settingsManager.updateModelSettings(it) } }
                )
            }
        }
    }
}

@Composable
fun ClockPreviewScreen(
    settings: ClockSettings,
    modelSettings: ModelSettings,
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    sunTimes: Pair<Double, Double>,
    celestialPositions: Triple<Double, Double, Double>,
    solarIrradiance: Int,
    calendarEvents: List<CalendarEvent>,
    sleepDebt: Double,
    title: String = "Home Screen Preview",
    showSetWallpaper: Boolean = true
) {
    val context = LocalContext.current
    val renderer = remember { ClockRenderer() }
    val now = remember { Calendar.getInstance() }
    
    LaunchedEffect(Unit) {
        while(true) {
            now.timeInMillis = System.currentTimeMillis()
            delay(10000) // Update every 10s for preview
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                renderer.draw(
                    canvas.nativeCanvas,
                    size.width.toInt(),
                    size.height.toInt(),
                    now,
                    sunTimes.first,
                    sunTimes.second,
                    sleepLogs,
                    celestialPositions.first,
                    celestialPositions.second,
                    celestialPositions.third,
                    solarIrradiance,
                    sleepDebt,
                    metrics.first,
                    calendarEvents,
                    showNumbers = settings.showNumbers,
                    showSleep = settings.showSleep,
                    showSunMoon = settings.showSunMoon,
                    showSleepDebtText = settings.showSleepDebtText,
                    showEnergy = settings.showEnergy,
                    showCalendar = settings.showCalendar,
                    smallTopRight = settings.smallTopRight,
                    showLifeCalendar = settings.showLifeCalendar,
                    showTotalBedtime = settings.showTotalBedtime,
                    showEnergyPct = settings.showEnergyPct,
                    normalizeEnergy = settings.normalizeEnergy,
                    includeNaps = modelSettings.includeNaps,
                    tauWake = modelSettings.tauWake,
                    tauSleep = modelSettings.tauSleep,
                    tauInertia = modelSettings.tauInertia,
                    debtFactor = modelSettings.debtFactor,
                    circadianOffset = modelSettings.circadianOffset,
                    useBathyphase = modelSettings.useBathyphase,
                    bedtimeGoal = modelSettings.bedtimeGoal,
                    showManualWake = modelSettings.showManualWake,
                    manualWakeTime = modelSettings.manualWakeTime,
                    isPreview = false
                )
            }
        }
        
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            style = MaterialTheme.typography.labelLarge
        )

        if (showSetWallpaper) {
            Button(
                onClick = {
                    val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, 
                            android.content.ComponentName(context, com.example.a24_hr_clock.wallpaper.ClockWallpaperService::class.java))
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                Icon(Icons.Default.Wallpaper, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Set Wallpaper")
            }
        }
    }
}

@Composable
fun ConnectivityScreen(
    isLoggedIn: Boolean,
    metrics: Triple<Double?, Double, Double>,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "Status & Connectivity", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        PermissionsChecklist()
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "FITBIT", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = if (isLoggedIn) "Status: Connected" else "Status: Not Connected",
            color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )

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
                Text(if (isLoggedIn) "Relink Fitbit" else "Login with Fitbit")
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
        Text(text = "GOOGLE CALENDAR", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        val hasCalendarPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        
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
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    context.sendBroadcast(Intent("com.example.a24_hr_clock.REFRESH_DATA"))
                    Toast.makeText(context, "Refreshing Calendar...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync Calendar Now")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, 
                        ComponentName(context, ClockWallpaperService::class.java))
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Wallpaper Picker")
        }
    }
}

@Composable
fun DisplaySettingsScreen(
    homeSettings: ClockSettings,
    lockSettings: ClockSettings,
    modelSettings: ModelSettings,
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    sunTimes: Pair<Double, Double>,
    celestialPositions: Triple<Double, Double, Double>,
    solarIrradiance: Int,
    calendarEvents: List<CalendarEvent>,
    sleepDebt: Double,
    onUpdateHome: (ClockSettings) -> Unit,
    onUpdateLock: (ClockSettings) -> Unit,
    onResetHome: () -> Unit,
    onResetLock: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val currentSettings = if (selectedTab == 0) homeSettings else lockSettings
    val updateFunc: (ClockSettings) -> Unit = if (selectedTab == 0) onUpdateHome else onUpdateLock
    var showPreview by remember { mutableStateOf(false) }

    if (showPreview) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPreview = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ClockPreviewScreen(
                    settings = currentSettings,
                    modelSettings = modelSettings,
                    sleepLogs = sleepLogs,
                    metrics = metrics,
                    sunTimes = sunTimes,
                    celestialPositions = celestialPositions,
                    solarIrradiance = solarIrradiance,
                    calendarEvents = calendarEvents,
                    sleepDebt = sleepDebt,
                    title = if (selectedTab == 0) "Home Screen Preview" else "Lock Screen Preview"
                )
                
                IconButton(
                    onClick = { showPreview = false },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).padding(top = 32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
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
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Home Screen", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Lock Screen", modifier = Modifier.padding(16.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showPreview = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Preview ${if (selectedTab == 0) "Home Screen" else "Lock Screen"}")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "ELEMENTS", style = MaterialTheme.typography.labelLarge)
        
        SettingToggle("Numbers", currentSettings.showNumbers) { 
            updateFunc(currentSettings.copy(showNumbers = it))
        }
        SettingToggle("Sun & Moon Icons", currentSettings.showSunMoon) {
            updateFunc(currentSettings.copy(showSunMoon = it))
        }
        SettingToggle("Small Clock in Top-Right", currentSettings.smallTopRight) {
            updateFunc(currentSettings.copy(smallTopRight = it))
        }
        SettingToggle("Life Calendar Background", currentSettings.showLifeCalendar) {
            updateFunc(currentSettings.copy(showLifeCalendar = it))
        }
        SettingToggle("Show Calendar Events", currentSettings.showCalendar) {
            updateFunc(currentSettings.copy(showCalendar = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "SLEEP", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Sleep on Clock", currentSettings.showSleep) {
            updateFunc(currentSettings.copy(showSleep = it))
        }
        SettingToggle("Show Sleep Debt Text", currentSettings.showSleepDebtText) {
            updateFunc(currentSettings.copy(showSleepDebtText = it))
        }
        SettingToggle("Show Time in Bed (On) vs Only Asleep (Off)", currentSettings.showTotalBedtime) {
            updateFunc(currentSettings.copy(showTotalBedtime = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "ENERGY", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Energy Curve", currentSettings.showEnergy) {
            updateFunc(currentSettings.copy(showEnergy = it))
        }
        SettingToggle("Show Energy %", currentSettings.showEnergyPct) {
            updateFunc(currentSettings.copy(showEnergyPct = it))
        }
        SettingToggle("Normalize Energy", currentSettings.normalizeEnergy) {
            updateFunc(currentSettings.copy(normalizeEnergy = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = if (selectedTab == 0) onResetHome else onResetLock,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset ${if (selectedTab == 0) "Home" else "Lock"} Defaults")
        }
    }
}

@Composable
fun ModelSettingsScreen(
    modelSettings: ModelSettings,
    onUpdate: (ModelSettings) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Advanced Sleep Model", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

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
        SettingToggle("Show Wake-up Indicator", modelSettings.showManualWake) {
            onUpdate(modelSettings.copy(showManualWake = it))
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

        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset Model Defaults")
        }
    }
}

@Composable
fun SleepLogScreen(
    sleepLogs: List<SleepLogEntry>,
    metrics: Triple<Double?, Double, Double>,
    modelSettings: ModelSettings,
    onUpdateModel: (ModelSettings) -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "Sleep Log (Last 14 Days)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
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

            // Header
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Inc", modifier = Modifier.weight(0.3f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Day", modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Date", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("Start", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("End", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Dur", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Eff", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Raw", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Wtd", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
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
                                onUpdateModel(modelSettings.copy(excludedDates = newExcluded))
                            },
                            modifier = Modifier.weight(0.3f).scale(0.7f)
                        )
                        Text(dayStr, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                        Text(dateStr, modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                        Text("—", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("—", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("0.0h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        Text("—", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                        
                        val debt = if (!isExcluded) sleepNeed else 0.0
                        val weightedDebt = debt * Math.pow(0.9, i.toDouble())
                        if (!isExcluded) { totalRaw += debt; totalWtd += weightedDebt; durations.add(0.0) }
                        
                        val debtColor = if (isExcluded) rowColor else Color(0xFFFF6B6B)
                        Text(text = if (isExcluded) "—" else String.format("%+.1f", debt), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, color = debtColor.copy(alpha = 0.5f), textAlign = TextAlign.End)
                        Text(text = if (isExcluded) "—" else String.format("%+.1f", weightedDebt), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, color = debtColor, textAlign = TextAlign.End)
                    }
                } else {
                    val totalDailyAsleep = logsForDay.sumOf { it.minutesAsleep / 60.0 }
                    val totalDailyBed = logsForDay.sumOf { it.timeInBed }
                    val dailyEff = if (totalDailyBed > 0) (logsForDay.sumOf { it.minutesAsleep.toDouble() } / totalDailyBed) else 0.0
                    
                    if (!isExcluded) {
                        durations.add(totalDailyAsleep)
                        efficiencies.add(dailyEff)
                        val debt = sleepNeed - totalDailyAsleep
                        totalRaw += debt
                        totalWtd += debt * Math.pow(0.9, i.toDouble())
                    }

                    logsForDay.sortedBy { it.startTime }.forEachIndexed { sessionIdx, log ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (sessionIdx == 0) {
                                Checkbox(
                                    checked = !isExcluded,
                                    onCheckedChange = { checked ->
                                        val newExcluded = if (checked) modelSettings.excludedDates.filter { it != date } else modelSettings.excludedDates + date
                                        onUpdateModel(modelSettings.copy(excludedDates = newExcluded))
                                    },
                                    modifier = Modifier.weight(0.3f).scale(0.7f)
                                )
                                Text(dayStr, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                                Text(dateStr, modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = rowColor)
                            } else {
                                Spacer(modifier = Modifier.weight(1.5f))
                            }

                            val startFmt = try { java.time.LocalDateTime.parse(log.startTime.replace("Z", "")).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) } catch (e: Exception) { log.startTime.take(5) }
                            val endFmt = try { java.time.LocalDateTime.parse(log.endTime.replace("Z", "")).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) } catch (e: Exception) { log.endTime.take(5) }
                            
                            Text(startFmt, modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            Text(endFmt, modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            Text("${String.format("%.1f", log.minutesAsleep/60.0)}h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)
                            
                            val sessEff = if (log.timeInBed > 0) (log.minutesAsleep.toDouble() / log.timeInBed * 100).toInt() else 0
                            Text("$sessEff%", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = rowColor)

                            if (sessionIdx == 0) {
                                val debt = sleepNeed - totalDailyAsleep
                                val weightedDebt = debt * Math.pow(0.9, i.toDouble())
                                val debtColor = if (isExcluded) rowColor else if (debt > 0.1) Color(0xFFFF6B6B) else if (debt < -0.1) Color(0xFF6BFF6B) else MaterialTheme.colorScheme.onSurface
                                Text(text = if (isExcluded) "—" else String.format("%+.1f", debt), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, color = debtColor.copy(alpha = 0.5f), textAlign = TextAlign.End)
                                Text(text = if (isExcluded) "—" else String.format("%+.1f", weightedDebt), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall, color = debtColor, textAlign = TextAlign.End)
                            } else {
                                Spacer(modifier = Modifier.weight(1.4f))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("AVG/TOT", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(2.0f))
                val avgDur = if (durations.isNotEmpty()) durations.average() else 0.0
                val avgEff = if (efficiencies.isNotEmpty()) efficiencies.average() else 0.0
                Text(text = "${String.format("%.1f", avgDur)}h", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text(text = "${(avgEff * 100).toInt()}%", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text(text = String.format("%+.1f", totalRaw), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = (if (totalRaw > 0.1) Color(0xFFFF6B6B) else if (totalRaw < -0.1) Color(0xFF6BFF6B) else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.5f))
                Text(text = String.format("%+.1f", totalWtd), modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, color = if (totalWtd > 0.1) Color(0xFFFF6B6B) else if (totalWtd < -0.1) Color(0xFF6BFF6B) else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
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

    val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasBackgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else {
        hasFineLocation
    }
    val locationOk = hasFineLocation && hasBackgroundLocation

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
            title = "1. Location: 'Allow all the time'",
            isOk = locationOk,
            onClick = {
                try {
                    val intent = Intent("android.intent.action.MANAGE_APP_PERMISSION")
                    intent.putExtra("android.intent.extra.PERMISSION_GROUP_NAME", "android.permission-group.LOCATION")
                    intent.putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
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
