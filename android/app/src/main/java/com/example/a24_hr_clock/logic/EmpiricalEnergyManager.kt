package com.example.a24_hr_clock.logic

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@Serializable
data class EnergyLog(
    val timestamp: Long,       // 30-min boundary timestamp in milliseconds
    val energyLevel: Int?,     // 0-100, null if missed
    val status: String         // "LOGGED", "MISSED", "SLEEP_EXCLUDED"
)

class EmpiricalEnergyManager(private val context: Context) {
    private val file = File(context.filesDir, "empirical_energy_logs.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val okHttpClient = OkHttpClient()
    private val prefs = context.getSharedPreferences("empirical_energy_prefs", Context.MODE_PRIVATE)

    // Helper to format ISO timestamps from Fitbit
    private fun parseIsoTimestamp(isoStr: String): Long {
        return try {
            val cleaned = if (isoStr.contains(".")) isoStr.substringBefore(".") else isoStr
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val ldt = LocalDateTime.parse(cleaned, formatter)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    // Align any timestamp to the nearest previous 30-min interval boundary
    fun alignTo30MinInterval(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val minute = cal.get(Calendar.MINUTE)
        if (minute < 30) {
            cal.set(Calendar.MINUTE, 0)
        } else {
            cal.set(Calendar.MINUTE, 30)
        }
        return cal.timeInMillis
    }

    // Load logs checking for public backup updates first
    fun loadLogs(): List<EnergyLog> {
        checkForPublicUpdate()
        return loadInternalLogs()
    }

    // Load logs directly from internal JSON file
    private fun loadInternalLogs(): List<EnergyLog> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            json.decodeFromString<List<EnergyLog>>(content)
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to load energy logs", e)
            emptyList()
        }
    }

    private fun checkForPublicUpdate() {
        try {
            val settings = SettingsManager(context)
            val modelSettings = runBlocking { settings.modelSettingsFlow.first() }
            val uriString = modelSettings.localBackupUri
            if (uriString.isNotEmpty()) {
                val treeUri = Uri.parse(uriString)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                val fileDoc = rootDoc?.findFile("Alertness_Master_Log.csv")
                if (fileDoc != null && fileDoc.exists()) {
                    val publicModified = fileDoc.lastModified()
                    val lastImported = prefs.getLong("last_public_modified", 0L)
                    if (publicModified > lastImported) {
                        Log.i("EmpiricalEnergyManager", "Newer public backup found ($publicModified > $lastImported). Importing...")
                        importFromPublicStorage(uriString)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to check for public update", e)
        }
    }

    // Save logs to JSON file
    fun saveLogs(logs: List<EnergyLog>, syncPublic: Boolean = true) {
        try {
            val content = json.encodeToString(logs)
            file.writeText(content)
            
            if (syncPublic) {
                val settings = SettingsManager(context)
                val modelSettings = runBlocking { settings.modelSettingsFlow.first() }
                val uriString = modelSettings.localBackupUri
                if (uriString.isNotEmpty()) {
                    syncToPublicStorage(uriString)
                }
            }
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to save energy logs", e)
        }
    }

    // Log or update a specific 30-min interval
    fun logEnergy(timestamp: Long, level: Int?) {
        val alignedTs = alignTo30MinInterval(timestamp)
        val logs = loadInternalLogs().toMutableList()
        val index = logs.indexOfFirst { it.timestamp == alignedTs }

        val status = if (level == null) "MISSED" else "LOGGED"
        val newLog = EnergyLog(alignedTs, level, status)

        if (index != -1) {
            logs[index] = newLog
        } else {
            logs.add(newLog)
        }
        logs.sortBy { it.timestamp }
        saveLogs(logs)
    }

    // Correlate existing logs against Fitbit sleep logs
    fun runRetroactiveSleepFilter(sleepLogs: List<SleepLogEntry>) {
        val logs = loadInternalLogs().toMutableList()
        var modified = false

        for (i in logs.indices) {
            val log = logs[i]
            var isInSleep = false
            for (sleep in sleepLogs) {
                val startMs = parseIsoTimestamp(sleep.startTime)
                val endMs = parseIsoTimestamp(sleep.endTime)
                if (startMs != 0L && endMs != 0L && log.timestamp in startMs..endMs) {
                    isInSleep = true
                    break
                }
            }

            if (isInSleep && log.status != "SLEEP_EXCLUDED") {
                logs[i] = log.copy(status = "SLEEP_EXCLUDED")
                modified = true
            } else if (!isInSleep && log.status == "SLEEP_EXCLUDED") {
                // Restore status back to logged/missed if it's no longer in sleep
                val status = if (log.energyLevel == null) "MISSED" else "LOGGED"
                logs[i] = log.copy(status = status)
                modified = true
            }
        }

        if (modified) {
            saveLogs(logs)
        }
    }

    // Generate expected 30-minute intervals starting from a date or first log
    fun getFullLogHistory(daysBack: Int = 30): List<EnergyLog> {
        val logs = loadLogs().associateBy { it.timestamp }
        val fullHistory = mutableListOf<EnergyLog>()

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysBack)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        val startTs = alignTo30MinInterval(cal.timeInMillis)

        val endTs = alignTo30MinInterval(System.currentTimeMillis())

        var currentTs = startTs
        while (currentTs <= endTs) {
            val existing = logs[currentTs]
            if (existing != null) {
                fullHistory.add(existing)
            } else {
                fullHistory.add(EnergyLog(currentTs, null, "MISSED"))
            }
            currentTs += 30 * 60 * 1000 // 30 minutes in ms
        }

        // Return sorted newest-first for UI convenience
        return fullHistory.sortedByDescending { it.timestamp }
    }

    // Check if there are any missed (empty) data points outside sleep times
    fun getMissedDataPointsCount(): Int {
        val logs = getFullLogHistory(30)
        return logs.count { it.energyLevel == null && it.status != "SLEEP_EXCLUDED" }
    }

    // Export formatted CSV data
    fun generateCSVExport(): String {
        val logs = getFullLogHistory(60) // export 60 days
        val sb = java.lang.StringBuilder()
        sb.append("Timestamp,DateTime,EnergyLevel,Status\n")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        for (log in logs) {
            val dateTimeStr = formatter.format(Instant.ofEpochMilli(log.timestamp))
            val levelStr = log.energyLevel?.toString() ?: ""
            sb.append("${log.timestamp},$dateTimeStr,$levelStr,${log.status}\n")
        }
        return sb.toString()
    }

    // Push the CSV data to a Google Apps Script deployment URL (Web App REST endpoint)
    suspend fun uploadToGoogleDrive(webAppUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (webAppUrl.isEmpty()) {
            return@withContext false to "Web App URL is empty"
        }

        val csvContent = generateCSVExport()
        val mediaType = "text/csv; charset=utf-8".toMediaType()
        val body = csvContent.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(webAppUrl)
            .post(body)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                true to "Successfully exported data to Google Drive"
            } else {
                false to "Server error: ${response.code} ${response.message}"
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        }
    }

    // Sync the master CSV log to a public storage directory (survives uninstall)
    fun syncToPublicStorage(uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        return try {
            val treeUri = Uri.parse(uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            
            // Look for or create the file
            var fileDoc = rootDoc.findFile("Alertness_Master_Log.csv")
            if (fileDoc == null) {
                fileDoc = rootDoc.createFile("text/csv", "Alertness_Master_Log.csv")
            }
            
            if (fileDoc != null) {
                context.contentResolver.openOutputStream(fileDoc.uri)?.use { os ->
                    os.write(generateCSVExport().toByteArray())
                }
                val currentModified = fileDoc.lastModified()
                prefs.edit().putLong("last_public_modified", currentModified).apply()
                true
            } else false
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to sync to public storage", e)
            false
        }
    }

    // Import logs from the public master CSV file (restore after reinstall)
    fun importFromPublicStorage(uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        return try {
            val treeUri = Uri.parse(uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val fileDoc = rootDoc.findFile("Alertness_Master_Log.csv") ?: return false
            
            val content = context.contentResolver.openInputStream(fileDoc.uri)?.bufferedReader()?.use { it.readText() } ?: return false
            val lines = content.lines()
            if (lines.isEmpty()) return false
            
            val newLogs = mutableListOf<EnergyLog>()
            // Skip header
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(",")
                if (parts.size >= 4) {
                    val ts = parts[0].toLongOrNull() ?: continue
                    val level = parts[2].toIntOrNull()
                    val status = parts[3]
                    newLogs.add(EnergyLog(ts, level, status))
                }
            }
            
            if (newLogs.isNotEmpty()) {
                // Merge with existing internal logs
                val existing = loadInternalLogs().associateBy { it.timestamp }.toMutableMap()
                for (log in newLogs) {
                    existing[log.timestamp] = log
                }
                saveLogs(existing.values.toList().sortedBy { it.timestamp }, syncPublic = false)
                val currentModified = fileDoc.lastModified()
                prefs.edit().putLong("last_public_modified", currentModified).apply()
                true
            } else false
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to import from public storage", e)
            false
        }
    }

    // Orchestrated auto-backup (to both Local and Drive). Returns true if sync happened.
    suspend fun autoBackup(settings: ModelSettings): Boolean {
        var didSync = false
        if (settings.localBackupUri.isNotEmpty()) {
            if (syncToPublicStorage(settings.localBackupUri)) {
                didSync = true
            }
        }
        if (settings.googleDriveUrl.isNotEmpty()) {
            val (success, msg) = uploadToGoogleDrive(settings.googleDriveUrl)
            Log.d("EmpiricalEnergyManager", "Auto-backup to Drive: $success, $msg")
            if (success) didSync = true
        }
        return didSync
    }

    // Read logs from the public storage master CSV file without saving
    fun readLogsFromPublicStorage(uriString: String): List<EnergyLog> {
        if (uriString.isEmpty()) return emptyList()
        return try {
            val treeUri = Uri.parse(uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            val fileDoc = rootDoc.findFile("Alertness_Master_Log.csv") ?: return emptyList()
            
            val content = context.contentResolver.openInputStream(fileDoc.uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
            val lines = content.lines()
            if (lines.isEmpty()) return emptyList()
            
            val parsedLogs = mutableListOf<EnergyLog>()
            // Skip header
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(",")
                if (parts.size >= 4) {
                    val ts = parts[0].toLongOrNull() ?: continue
                    val level = parts[2].toIntOrNull()
                    val status = parts[3]
                    parsedLogs.add(EnergyLog(ts, level, status))
                }
            }
            parsedLogs
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to read logs from public storage", e)
            emptyList()
        }
    }

    // Update the last public modified timestamp in preferences
    fun updateLastPublicModifiedTime(uriString: String) {
        if (uriString.isEmpty()) return
        try {
            val treeUri = Uri.parse(uriString)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            val fileDoc = rootDoc?.findFile("Alertness_Master_Log.csv")
            if (fileDoc != null) {
                val currentModified = fileDoc.lastModified()
                prefs.edit().putLong("last_public_modified", currentModified).apply()
            }
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to update last public modified time", e)
        }
    }

    // Merges internal app logs and public storage CSV logs based on precedence rules
    fun mergeLogs(appLogs: List<EnergyLog>, publicLogs: List<EnergyLog>): MergeResult {
        val appMap = appLogs.associateBy { it.timestamp }
        val publicMap = publicLogs.associateBy { it.timestamp }
        
        val allTimestamps = (appMap.keys + publicMap.keys).sorted()
        val merged = mutableListOf<EnergyLog>()
        val conflicts = mutableListOf<MergeConflict>()
        
        for (ts in allTimestamps) {
            val appLog = appMap[ts]
            val publicLog = publicMap[ts]
            
            if (appLog != null && publicLog != null) {
                val appVal = appLog.energyLevel
                val publicVal = publicLog.energyLevel
                
                if (appVal != null && publicVal != null) {
                    if (appVal == publicVal) {
                        merged.add(appLog)
                    } else {
                        // Conflict! We temporarily place the appLog (since app value takes precedence)
                        // but record the conflict so the user can choose.
                        conflicts.add(MergeConflict(ts, appVal, publicVal))
                        merged.add(appLog)
                    }
                } else if (appVal != null) {
                    // Logged value takes precedence over no value
                    merged.add(appLog)
                } else if (publicVal != null) {
                    // Logged value takes precedence over no value
                    merged.add(publicLog)
                } else {
                    // Both null/missed, keep app status
                    merged.add(appLog)
                }
            } else if (appLog != null) {
                merged.add(appLog)
            } else if (publicLog != null) {
                merged.add(publicLog)
            }
        }
        return MergeResult(merged, conflicts)
    }
}

@Serializable
data class MergeConflict(
    val timestamp: Long,
    val appValue: Int,
    val publicValue: Int
)

data class MergeResult(
    val mergedLogs: List<EnergyLog>,
    val conflicts: List<MergeConflict>
)
