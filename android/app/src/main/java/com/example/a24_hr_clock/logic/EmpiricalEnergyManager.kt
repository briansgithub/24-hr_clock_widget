package com.example.a24_hr_clock.logic

import android.content.Context
import android.util.Log
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

    // Load logs from JSON file
    fun loadLogs(): List<EnergyLog> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            json.decodeFromString<List<EnergyLog>>(content)
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to load energy logs", e)
            emptyList()
        }
    }

    // Save logs to JSON file
    fun saveLogs(logs: List<EnergyLog>) {
        try {
            val content = json.encodeToString(logs)
            file.writeText(content)
        } catch (e: Exception) {
            Log.e("EmpiricalEnergyManager", "Failed to save energy logs", e)
        }
    }

    // Log or update a specific 30-min interval
    fun logEnergy(timestamp: Long, level: Int?) {
        val alignedTs = alignTo30MinInterval(timestamp)
        val logs = loadLogs().toMutableList()
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
        val logs = loadLogs().toMutableList()
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
    fun uploadToGoogleDrive(webAppUrl: String, callback: (Boolean, String) -> Unit) {
        if (webAppUrl.isEmpty()) {
            callback(false, "Web App URL is empty")
            return
        }

        val csvContent = generateCSVExport()
        val mediaType = "text/csv; charset=utf-8".toMediaType()
        val body = csvContent.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(webAppUrl)
            .post(body)
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    callback(true, "Successfully exported data to Google Drive")
                } else {
                    callback(false, "Server error: ${response.code} ${response.message}")
                }
            }
        })
    }
}
