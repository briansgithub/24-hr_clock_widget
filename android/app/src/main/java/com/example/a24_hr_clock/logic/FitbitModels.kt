package com.example.a24_hr_clock.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class FitbitTokens(
    @SerialName("access_token") val access_token: String,
    @SerialName("refresh_token") val refresh_token: String,
    @SerialName("expires_in") val expires_in: Int,
    @SerialName("user_id") val user_id: String
)

@Serializable
data class SleepResponse(
    val sleep: List<SleepLogEntry>? = null,
    val meta: SleepProcessingMeta? = null
)

@Serializable
data class SleepProcessingMeta(val state: String = "", val retryDuration: Long? = null)

@Serializable
data class SleepLogEntry(
    val dateOfSleep: String,
    val minutesAsleep: Int,
    val isMainSleep: Boolean,
    val timeInBed: Int,
    val startTime: String,
    val endTime: String,
    val duration: Long = 0,
    val sourceRecordId: String? = null
)

@Serializable
data class HeartRateResponse(
    @SerialName("activities-heart")
    val activities_heart: List<HeartRateSummaryEntry>? = null,
    @SerialName("activities-heart-intraday")
    val activities_heart_intraday: IntradayDataset? = null
)

@Serializable
data class HeartRateSummaryEntry(
    val dateTime: String,
    val value: HeartRateSummaryValue
)

@Serializable
data class HeartRateSummaryValue(
    val restingHeartRate: Int? = null
)

@Serializable
data class IntradayDataset(
    val dataset: List<HeartRatePointEntry>
)

@Serializable
data class HeartRatePointEntry(
    val time: String,
    val value: Int
)

@Serializable
data class HRVResponse(
    val hrv: List<HRVEntry>
)

@Serializable
data class HRVEntry(
    val dateTime: String,
    val value: HRVValue
)

@Serializable
data class HRVValue(
    val dailyRmssd: Double
)

@Serializable
data class DailyExerciseMetrics(
    val date: String,
    val trimp: Double,
    val hrv: Double?,
    val rhr: Double? = null,
    val hrss: Double = 0.0,
    val hrvMissing: Boolean = false,
    val hrMissing: Boolean = false,
    val dataStatus: String = "valid" // "valid", "incomplete", "missing"
)
