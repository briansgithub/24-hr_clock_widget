package com.example.a24_hr_clock.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a24_hr_clock.logic.EnergyLog
import com.example.a24_hr_clock.logic.EmpiricalEnergyManager
import com.example.a24_hr_clock.logic.ModelSettings
import com.example.a24_hr_clock.logic.MergeConflict
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyLogInputScreen(
    onSave: (Int) -> Unit,
    onCancel: () -> Unit,
    initialValue: String = "",
    entryTimestamp: Long = System.currentTimeMillis()
) {
    var inputVal by remember { mutableStateOf(initialValue) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val alignedTs = remember(entryTimestamp) {
        EmpiricalEnergyManager(context).alignTo30MinInterval(entryTimestamp)
    }
    val entryDateTimeLabel = remember(alignedTs) {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(alignedTs), ZoneId.systemDefault())
        val dateStr = DateTimeFormatter.ofPattern("MMM dd, yyyy").withLocale(Locale.US).format(ldt)
        val timeStr = DateTimeFormatter.ofPattern("hh:mm a").withLocale(Locale.US).format(ldt)
        "$dateStr at $timeStr"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Current Alertness") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Logging for $entryDateTimeLabel",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = "How energetic do you feel right now?",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Enter a rating from 0 (exhausted) to 100 (fully charged).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = inputVal,
                onValueChange = {
                    inputVal = it
                    errorMsg = null
                },
                label = { Text("Energy Level (0-100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errorMsg != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            errorMsg?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val parsed = inputVal.toIntOrNull()
                    if (parsed != null && parsed in 0..100) {
                        onSave(parsed)
                    } else {
                        errorMsg = "Please enter a valid whole number between 0 and 100."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Save Entry", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmpiricalLogHistoryScreen(
    manager: EmpiricalEnergyManager,
    modelSettings: ModelSettings,
    onUpdateGoogleDriveUrl: (String) -> Unit,
    onUpdateLocalBackupUri: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(emptyList<EnergyLog>()) }
    var selectedLogForEdit by remember { mutableStateOf<EnergyLog?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    
    var activeConflicts by remember { mutableStateOf<List<MergeConflict>>(emptyList()) }
    var tempMergedLogs by remember { mutableStateOf<List<EnergyLog>>(emptyList()) }
    var resolvedConflictValues by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var showConflictDialog by remember { mutableStateOf(false) }

    // Google Drive script config — fall back to the known deployment URL when unset
    val initialDriveUrl = modelSettings.googleDriveUrl.ifEmpty {
        ModelSettings.DEFAULT_GOOGLE_DRIVE_WEB_APP_URL
    }
    var driveUrlInput by remember { mutableStateOf(initialDriveUrl) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(modelSettings.googleDriveUrl) {
        if (modelSettings.googleDriveUrl.isEmpty()) {
            onUpdateGoogleDriveUrl(ModelSettings.DEFAULT_GOOGLE_DRIVE_WEB_APP_URL)
        } else if (driveUrlInput != modelSettings.googleDriveUrl) {
            driveUrlInput = modelSettings.googleDriveUrl
        }
    }

    // Local Storage Backup
    var localBackupStatusMessage by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Take persistable permission
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            
            onUpdateLocalBackupUri(uri.toString())
        }
    }

    fun refreshData() {
        logs = manager.getFullLogHistory(30) // Show last 30 days
    }

    fun triggerSync() {
        if (modelSettings.localBackupUri.isEmpty()) {
            localBackupStatusMessage = "Sync failed. No folder linked."
            return
        }
        val appLogs = manager.getFullLogHistory(60) // Merge last 60 days
        val publicLogs = manager.readLogsFromPublicStorage(modelSettings.localBackupUri)
        
        if (publicLogs.isEmpty()) {
            // Public file doesn't exist or is empty, just write current app logs to public storage
            if (manager.syncToPublicStorage(modelSettings.localBackupUri)) {
                localBackupStatusMessage = "Successfully synced current logs to backup"
                refreshData()
            } else {
                localBackupStatusMessage = "Sync failed. Check folder permissions."
            }
            return
        }
        
        val result = manager.mergeLogs(appLogs, publicLogs)
        if (result.conflicts.isEmpty()) {
            // No conflicts, save directly
            manager.saveLogs(result.mergedLogs, syncPublic = true)
            // Update preferences last modified
            manager.updateLastPublicModifiedTime(modelSettings.localBackupUri)
            localBackupStatusMessage = "Sync Complete: Merged successfully"
            refreshData()
        } else {
            // Show conflicts dialog
            tempMergedLogs = result.mergedLogs
            activeConflicts = result.conflicts
            // Default to app value for all conflicts initially
            resolvedConflictValues = result.conflicts.associate { it.timestamp to it.appValue }
            showConflictDialog = true
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    // Edit value dialog
    if (showEditDialog && selectedLogForEdit != null) {
        val target = selectedLogForEdit!!
        val dateStr = DateTimeFormatter.ofPattern("MMM dd, yyyy").withLocale(Locale.US)
            .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(target.timestamp), ZoneId.systemDefault()))
        val timeStr = DateTimeFormatter.ofPattern("hh:mm a").withLocale(Locale.US)
            .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(target.timestamp), ZoneId.systemDefault()))

        var editVal by remember { mutableStateOf(target.energyLevel?.toString() ?: "") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Log for $dateStr at $timeStr") },
            text = {
                Column {
                    Text(
                        text = "Enter energy rating (0-100) or leave blank to clear this log:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = editVal,
                        onValueChange = {
                            editVal = it
                            dialogError = null
                        },
                        label = { Text("Energy Level (0-100)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = dialogError != null,
                        singleLine = true
                    )
                    dialogError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editVal.isEmpty()) {
                            manager.logEnergy(target.timestamp, null)
                            showEditDialog = false
                            refreshData()
                        } else {
                            val parsed = editVal.toIntOrNull()
                            if (parsed != null && parsed in 0..100) {
                                manager.logEnergy(target.timestamp, parsed)
                                showEditDialog = false
                                refreshData()
                            } else {
                                dialogError = "Enter a number from 0 to 100."
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Merge conflicts dialog
    if (showConflictDialog && activeConflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("Merge Conflicts Detected") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text(
                        text = "We found different scores for the same time slots. Select which value to keep:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Bulk Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                resolvedConflictValues = activeConflicts.associate { it.timestamp to it.appValue }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Use App for All", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                resolvedConflictValues = activeConflicts.associate { it.timestamp to it.publicValue }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Use Backup for All", fontSize = 11.sp)
                        }
                    }

                    HorizontalDivider()

                    // Scrollable list of conflicts
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(activeConflicts) { conflict ->
                            val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(conflict.timestamp), ZoneId.systemDefault())
                            val dateStr = DateTimeFormatter.ofPattern("MMM dd, hh:mm a").withLocale(Locale.US).format(ldt)
                            
                            val selectedVal = resolvedConflictValues[conflict.timestamp]

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = dateStr,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // App Value Button
                                        OutlinedCard(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    resolvedConflictValues = resolvedConflictValues + (conflict.timestamp to conflict.appValue)
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (selectedVal == conflict.appValue) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                            )
                                        ) {
                                            Text(
                                                text = "App: ${conflict.appValue}",
                                                textAlign = TextAlign.Center,
                                                fontWeight = if (selectedVal == conflict.appValue) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                                            )
                                        }

                                        // Public Value Button
                                        OutlinedCard(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    resolvedConflictValues = resolvedConflictValues + (conflict.timestamp to conflict.publicValue)
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (selectedVal == conflict.publicValue) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                            )
                                        ) {
                                            Text(
                                                text = "Backup: ${conflict.publicValue}",
                                                textAlign = TextAlign.Center,
                                                fontWeight = if (selectedVal == conflict.publicValue) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalLogs = tempMergedLogs.map { log ->
                            val resolvedVal = resolvedConflictValues[log.timestamp]
                            if (resolvedVal != null) {
                                log.copy(energyLevel = resolvedVal, status = "LOGGED")
                            } else {
                                log
                            }
                        }
                        manager.saveLogs(finalLogs, syncPublic = true)
                        manager.updateLastPublicModifiedTime(modelSettings.localBackupUri)
                        showConflictDialog = false
                        localBackupStatusMessage = "Sync Complete: Conflicts resolved and merged"
                        refreshData()
                    }
                ) {
                    Text("Confirm Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConflictDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Empirical Energy History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        // Group list items by formatted date string
        val formatterDate = DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy").withLocale(Locale.US)
        val formatterTime = DateTimeFormatter.ofPattern("hh:mm a").withLocale(Locale.US)

        val groupedLogs = remember(logs) {
            logs.groupBy {
                formatterDate.format(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneId.systemDefault())
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                // Local Storage Backup Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "On-Device Persistence (Survives Uninstall)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Data is saved as CSV in your Documents folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (modelSettings.localBackupUri.isEmpty()) {
                            Button(
                                onClick = { launcher.launch(null) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Select Backup Folder")
                            }
                        } else {
                            Text(
                                text = "Status: Linked to Documents",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    triggerSync()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Sync")
                            }
                            TextButton(
                                onClick = { onUpdateLocalBackupUri("") },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Unlink Folder", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        localBackupStatusMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("Successfully") || it.contains("synced")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                // Google Drive config box
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Google Drive Auto-Export Config",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Web App Deployment URL",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = driveUrlInput,
                            onValueChange = {
                                driveUrlInput = it
                                onUpdateGoogleDriveUrl(it)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    isExporting = true
                                    exportStatusMessage = "Exporting..."
                                    scope.launch {
                                        val (_, msg) = manager.uploadToGoogleDrive(driveUrlInput)
                                        isExporting = false
                                        exportStatusMessage = msg
                                    }
                                },
                                enabled = !isExporting && driveUrlInput.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Export Now")
                            }
                        }
                        exportStatusMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("Successfully")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Past 30 Days Logging History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            groupedLogs.forEach { (dateHeader, dayLogs) ->
                item {
                    Text(
                        text = dateHeader,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = 16.dp, bottom = 8.dp)
                    )
                }

                items(dayLogs) { log ->
                    val localTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(log.timestamp),
                        ZoneId.systemDefault()
                    )
                    val timeStrStart = formatterTime.format(localTime)
                    val timeStrEnd = formatterTime.format(localTime.plusMinutes(30))

                    val (statusText, statusColor) = when (log.status) {
                        "LOGGED" -> "✓ ${log.energyLevel} / 100" to Color(0xFF1B5E20)
                        "SLEEP_EXCLUDED" -> "💤 Sleep (Ignored)" to Color(0xFF616161)
                        else -> "⚠️ Missed (Tap to fill)" to Color(0xFFB71C1C)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedLogForEdit = log
                                showEditDialog = true
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = when (log.status) {
                                "LOGGED" -> Color(0xFFE8F5E9)
                                "SLEEP_EXCLUDED" -> Color(0xFFF5F5F5)
                                else -> Color(0xFFFFEBEE)
                            },
                            contentColor = when (log.status) {
                                "LOGGED" -> Color(0xFF1B5E20)
                                "SLEEP_EXCLUDED" -> Color(0xFF212121)
                                else -> Color(0xFFB71C1C)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "$timeStrStart – $timeStrEnd",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
