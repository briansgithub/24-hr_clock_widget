package com.example.a24_hr_clock.logic

import kotlin.math.*

object EnergyCalculator {

    fun twoProcessEnergy(
        t: Double,
        sleepDebtHours: Double = 0.0,
        sleepDuration: Double = 7.5,
        circadianPeakOffset: Double = 10.0,
        clamp: Boolean = true
    ): Double {
        // -- 1. PROCESS S - Homeostatic Sleep Pressure ----------------------------
        val tauWake = 18.0
        val tauSleep = 4.0
        val sMax = 1.0
        val sMin = 0.05

        val sleepStart = 24.0 - sleepDuration
        val eW = exp(-sleepStart / tauWake)
        val eS = exp(-sleepDuration / tauSleep)

        var divisor = 1.0 - eW * eS
        if (divisor < 0.0001) {
            divisor = 0.0001
        }

        val s0 = (sMax * eS * (1.0 - eW) + sMin * (1.0 - eS)) / divisor
        val sBed = s0 * eW + sMax * (1.0 - eW)

        val sT = if (t < sleepStart) {
            s0 + (sMax - s0) * (1.0 - exp(-t / tauWake))
        } else {
            val tSleep = t - sleepStart
            sMin + (sBed - sMin) * exp(-tSleep / tauSleep)
        }

        // -- 2. PROCESS C - Circadian Alerting Signal -----------------------------
        val cPrimary = 0.55 * cos((PI / 12.0) * (t - circadianPeakOffset))
        val cSecondary = -0.15 * cos((PI / 6.0) * (t - 8.0))
        val cT = 1.10 + cPrimary + cSecondary

        // -- 3. RAW ALERTNESS = C(t) - S(t) ---------------------------------------
        val raw = cT - sT

        // -- 4. SLEEP INERTIA (first ~90 min) -------------------------------------
        val tauInertia = 0.6
        val inertiaGate = 1.0 - exp(-t / tauInertia)

        // -- 5. DEBT PENALTY (shifts floor down) ----------------------------------
        val debtPenalty = min(0.35, sleepDebtHours / 25.0)

        val alertness = (raw * inertiaGate) - debtPenalty

        return if (clamp) {
            max(0.0, min(1.0, alertness))
        } else {
            alertness
        }
    }

    fun getEnergyLevel(
        hClock: Double,
        wakeHour: Double,
        sleepDebtHours: Double = 0.0,
        sleepDuration: Double = 7.5,
        bathyphaseHour: Double? = null,
        clamp: Boolean = true
    ): Double {
        val t = (hClock - wakeHour).mod(24.0)

        // Calculate peak_offset from bathyphase (matches Python's get_energy_level)
        val peakOffset = if (bathyphaseHour != null) {
            // Peak is roughly 5 hours after bathyphase
            val peakH = (bathyphaseHour + 5.0).mod(24.0)
            (peakH - wakeHour).mod(24.0)
        } else {
            10.0 // sensible population-average fallback
        }

        return twoProcessEnergy(t, sleepDebtHours, sleepDuration, peakOffset, clamp)
    }

    fun computeSleepDebt(
        sleepLogs: List<SleepLog>,
        sleepNeedHours: Double,
        includeNaps: Boolean = true
    ): Double {
        val dailySleep = mutableMapOf<String, Double>()
        for (log in sleepLogs) {
            val date = log.dateOfSleep
            if (date.isEmpty() || (!includeNaps && !log.isMainSleep)) continue
            dailySleep[date] = dailySleep.getOrDefault(date, 0.0) + (log.minutesAsleep / 60.0)
        }

        val todayStr = java.time.LocalDate.now().toString()
        val sortedDates = dailySleep.keys
            .filter { it != todayStr || (dailySleep[it] ?: 0.0) > 0.0 }
            .sortedDescending()
            
        val windowDates = sortedDates.take(14)

        var weightedDebt = 0.0
        val decayFactor = 0.9

        windowDates.forEachIndexed { i, date ->
            val actual = dailySleep[date] ?: 0.0
            val nightlyDebt = max(0.0, sleepNeedHours - actual)
            val weight = decayFactor.pow(i)
            weightedDebt += nightlyDebt * weight
        }

        return weightedDebt
    }

    fun findBathyphase(intradayHr: List<HeartRatePoint>): Double? {
        if (intradayHr.isEmpty()) return null

        val buckets = mutableMapOf<Int, MutableList<Double>>()
        for (p in intradayHr) {
            val h = p.time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            buckets.getOrPut(h) { mutableListOf() }.add(p.value.toDouble())
        }

        if (buckets.isEmpty()) return null

        val avgByHour = buckets.mapValues { it.value.average() }
        return avgByHour.minByOrNull { it.value }?.key?.toDouble()
    }
}

data class SleepLog(
    val dateOfSleep: String,
    val minutesAsleep: Int,
    val isMainSleep: Boolean,
    val timeInBed: Int
)

data class HeartRatePoint(
    val time: String,
    val value: Int
)
