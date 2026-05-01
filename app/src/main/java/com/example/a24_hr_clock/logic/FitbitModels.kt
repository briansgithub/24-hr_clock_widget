package com.example.a24_hr_clock.logic

import kotlinx.serialization.Serializable

@Serializable
data class FitbitTokens(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val user_id: String
)

@Serializable
data class SleepResponse(
    val sleep: List<SleepLogEntry>
)

@Serializable
data class SleepLogEntry(
    val dateOfSleep: String,
    val minutesAsleep: Int,
    val isMainSleep: Boolean,
    val timeInBed: Int,
    val startTime: String,
    val endTime: String
)

@Serializable
data class HeartRateResponse(
    val activities_heart_intraday: IntradayDataset? = null
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
