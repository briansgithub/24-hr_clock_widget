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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.a24_hr_clock.logic.ClockSettings
import com.example.a24_hr_clock.logic.FitbitManager
import com.example.a24_hr_clock.logic.SettingsManager
import com.example.a24_hr_clock.logic.SleepLogEntry
import com.example.a24_hr_clock.ui.theme._24_hr_clockTheme
import com.example.a24_hr_clock.wallpaper.ClockWallpaperService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var fitbitManager: FitbitManager
    private lateinit var settingsManager: SettingsManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            Toast.makeText(this, "Location permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fitbitManager.getAuthUrl()))
                            startActivity(intent)
                        }
                    )
                }
            }
        }

        handleIntent(intent)
    }

    private fun checkPermissions() {
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (coarse != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
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
                        Toast.makeText(this@MainActivity, "Fitbit Login Successful!", Toast.LENGTH_SHORT).show()
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
    onLoginClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoggedIn by remember { mutableStateOf(false) }
    var sleepLogs by remember { mutableStateOf<List<SleepLogEntry>>(emptyList()) }
    var metrics by remember { mutableStateOf<Triple<Double?, Double, Double>>(Triple(null, 1.0, 9.75)) }
    
    val homeSettings by settingsManager.homeSettingsFlow.collectAsState(initial = ClockSettings())
    val lockSettings by settingsManager.lockSettingsFlow.collectAsState(initial = ClockSettings())
    
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isLoggedIn = fitbitManager.isLoggedIn()
        if (isLoggedIn) {
            sleepLogs = fitbitManager.getLastSleepLogs()
            metrics = fitbitManager.getMetrics()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "24h Clock Wallpaper Settings", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = if (isLoggedIn) "Status: Connected to Fitbit" else "Status: Not Connected",
            color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )

        if (isLoggedIn) {
            val (bathy, eff, need) = metrics
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
        
        Button(onClick = onLoginClick) {
            Text(if (isLoggedIn) "Relink Fitbit" else "Login with Fitbit")
        }

        if (isLoggedIn) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        Toast.makeText(context, "Refreshing Fitbit data...", Toast.LENGTH_SHORT).show()
                        context.sendBroadcast(Intent("com.example.a24_hr_clock.REFRESH_DATA"))
                        kotlinx.coroutines.delay(2000)
                        sleepLogs = fitbitManager.getLastSleepLogs()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("API Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Home Screen", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Lock Screen", modifier = Modifier.padding(16.dp))
            }
        }

        val currentSettings = if (selectedTab == 0) homeSettings else lockSettings
        val updateFunc: (ClockSettings) -> Unit = { updated ->
            scope.launch {
                if (selectedTab == 0) settingsManager.updateHomeSettings(updated)
                else settingsManager.updateLockSettings(updated)
            }
        }



        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "DISPLAY", style = MaterialTheme.typography.labelLarge)
        
        SettingToggle("Numbers", currentSettings.showNumbers) { 
            updateFunc(currentSettings.copy(showNumbers = it))
        }
        
        SettingToggle("Sun & Moon Icons", currentSettings.showSunMoon) {
            updateFunc(currentSettings.copy(showSunMoon = it))
        }
        
        SettingToggle("Small Clock in Top-Right", currentSettings.smallTopRight) {
            updateFunc(currentSettings.copy(smallTopRight = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "SLEEP", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Sleep on Clock", currentSettings.showSleep) {
            updateFunc(currentSettings.copy(showSleep = it))
        }
        
        SettingToggle("Show Sleep Debt Text", currentSettings.showSleepDebtText) {
            updateFunc(currentSettings.copy(showSleepDebtText = it))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "ENERGY", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Energy Curve", currentSettings.showEnergy) {
            updateFunc(currentSettings.copy(showEnergy = it))
        }

        Spacer(modifier = Modifier.height(32.dp))
        
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
            Text("Launch Wallpaper Picker")
        }

        if (isLoggedIn && sleepLogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "SLEEP LOG (Last 14 Days)", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium)
                    .padding(8.dp)
            ) {
                // Header
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text("Day", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Date", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Start", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Dur", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Eff", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Debt", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                
                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                sleepLogs.sortedByDescending { it.dateOfSleep }.take(14).forEach { log ->
                    val (bathy, eff, sleepNeed) = metrics
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        val dateObj = try { java.time.LocalDate.parse(log.dateOfSleep) } catch (e: Exception) { null }
                        val dayStr = dateObj?.format(java.time.format.DateTimeFormatter.ofPattern("E")) ?: ""
                        val dateStr = dateObj?.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd")) ?: log.dateOfSleep.takeLast(5)
                        
                        Text(dayStr, modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall)
                        Text(dateStr, modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall)
                        
                        val startFmt = try {
                            java.time.LocalDateTime.parse(log.startTime.replace("Z", ""))
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        } catch (e: Exception) { log.startTime.take(5) }
                        
                        Text(startFmt, modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall)
                        
                        val asleepH = log.minutesAsleep / 60.0
                        Text("${String.format("%.1f", asleepH)}h", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
                        
                        val rowEff = if (log.timeInBed > 0) (log.minutesAsleep.toDouble() / log.timeInBed) * 100 else 0.0
                        Text("${rowEff.toInt()}%", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
                        
                        val debt = if (log.isMainSleep) sleepNeed - asleepH else -asleepH
                        val debtColor = if (debt > 0.1) Color(0xFFFF6B6B) else if (debt < -0.1) Color(0xFF6BFF6B) else MaterialTheme.colorScheme.onSurface
                        Text(
                            text = String.format("%+.1fh", debt),
                            modifier = Modifier.weight(0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            color = debtColor
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
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
