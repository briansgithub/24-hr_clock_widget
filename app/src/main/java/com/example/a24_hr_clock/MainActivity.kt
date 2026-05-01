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
    val settings by settingsManager.settingsFlow.collectAsState(initial = ClockSettings())

    LaunchedEffect(Unit) {
        isLoggedIn = fitbitManager.isLoggedIn()
        if (isLoggedIn) {
            sleepLogs = fitbitManager.getLastSleepLogs()
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
                        // Update logs in UI after a short delay
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

        Text(text = "DISPLAY", style = MaterialTheme.typography.labelLarge)
        
        SettingToggle("Numbers", settings.showNumbers) { 
            scope.launch { settingsManager.updateSettings(settings.copy(showNumbers = it)) }
        }
        
        SettingToggle("Sun & Moon Icons", settings.showSunMoon) {
            scope.launch { settingsManager.updateSettings(settings.copy(showSunMoon = it)) }
        }
        
        SettingToggle("Small Clock in Top-Right", settings.smallTopRight) {
            scope.launch { settingsManager.updateSettings(settings.copy(smallTopRight = it)) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "SLEEP", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Sleep on Clock", settings.showSleep) {
            scope.launch { settingsManager.updateSettings(settings.copy(showSleep = it)) }
        }
        
        SettingToggle("Show Sleep Debt Text", settings.showSleepDebtText) {
            scope.launch { settingsManager.updateSettings(settings.copy(showSleepDebtText = it)) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "ENERGY", style = MaterialTheme.typography.labelLarge)

        SettingToggle("Show Energy Curve", settings.showEnergy) {
            scope.launch { settingsManager.updateSettings(settings.copy(showEnergy = it)) }
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
                    Text("Date", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Start", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Duration", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Main", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                
                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                sleepLogs.sortedByDescending { it.dateOfSleep }.take(14).forEach { log ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(log.dateOfSleep, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                        
                        val startFmt = try {
                            java.time.LocalDateTime.parse(log.startTime.replace("Z", ""))
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        } catch (e: Exception) { log.startTime.take(5) }
                        
                        Text(startFmt, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("${String.format("%.1f", log.minutesAsleep / 60.0)}h", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                        Text(if (log.isMainSleep) "Y" else "N", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
