package com.example.a24_hr_clock.logic

import kotlin.math.*

object EnergyCalculator {

    fun twoProcessEnergy(
        t: Double,
        sleepDebtHours: Double = 0.0,
        sleepDuration: Double = 7.5,
        circadianPeakOffset: Double = 10.0,
        clamp: Boolean = true,
        tauWake: Double = 18.2,
        tauSleep: Double = 4.2,
        tauInertia: Double = 1.5,
        debtFactor: Double = 1.0
    ): Double {
        // -- 1. PROCESS S - Homeostatic Sleep Pressure ----------------------------
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
        // W(t) = (raw_at_wake) * exp(-t / tau_inertia)
        val cAtWake = 1.10 + 0.55 * cos((PI / 12.0) * (-circadianPeakOffset)) +
                     -0.15 * cos((PI / 6.0) * (-8.0))
        val rawAtWake = cAtWake - s0
        val inertiaPenalty = max(0.0, rawAtWake) * exp(-t / tauInertia)

        // -- 5. DEBT PENALTY (shifts floor down) ----------------------------------
        val debtPenalty = min(0.35, (sleepDebtHours * debtFactor) / 25.0)

        val alertness = raw - inertiaPenalty - debtPenalty

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
        clamp: Boolean = true,
        tauWake: Double = 18.2,
        tauSleep: Double = 4.2,
        tauInertia: Double = 1.5,
        debtFactor: Double = 1.0,
        circadianPeakOffset: Double? = null
    ): Double {
        val t = (hClock - wakeHour).mod(24.0)

        // Calculate peak_offset
        val peakOffset = if (circadianPeakOffset != null) {
            circadianPeakOffset
        } else if (bathyphaseHour != null) {
            // WMZ peak is roughly 15 hours after bathyphase
            val peakH = (bathyphaseHour + 15.0).mod(24.0)
            (peakH - wakeHour).mod(24.0)
        } else {
            10.0 // sensible population-average fallback
        }

        return twoProcessEnergy(
            t, sleepDebtHours, sleepDuration, peakOffset, clamp,
            tauWake, tauSleep, tauInertia, debtFactor
        )
    }

    fun computeSleepDebt(
        sleepLogs: List<SleepLog>,
        sleepNeedHours: Double,
        includeNaps: Boolean = true,
        excludedDates: List<String> = emptyList()
    ): Double {
        val dailySleep = mutableMapOf<String, Double>()
        for (log in sleepLogs) {
            val date = log.dateOfSleep
            if (date.isEmpty() || (!includeNaps && !log.isMainSleep)) continue
            dailySleep[date] = dailySleep.getOrDefault(date, 0.0) + (log.minutesAsleep / 60.0)
        }

        val today = java.time.LocalDate.now()
        var weightedDebt = 0.0
        val decayFactor = 0.9
        var weightIndex = 0

        // Process T-0 down to T-14
        for (i in 0 until 15) {
            val dateStr = today.minusDays(i.toLong()).toString()
            if (excludedDates.contains(dateStr)) continue

            val actual = dailySleep[dateStr] ?: 0.0
            val nightlyDebt = sleepNeedHours - actual
            val weight = decayFactor.pow(weightIndex)
            weightedDebt += nightlyDebt * weight
            
            weightIndex++
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

    fun circAvg(hoursList: List<Double>): Double? {
        if (hoursList.isEmpty()) return null
        
        var avgSin = 0.0
        var avgCos = 0.0
        
        for (h in hoursList) {
            val rad = h * 2.0 * PI / 24.0
            avgSin += sin(rad)
            avgCos += cos(rad)
        }
        
        avgSin /= hoursList.size
        avgCos /= hoursList.size
        
        val avgRad = atan2(avgSin, avgCos)
        return (avgRad * 24.0 / (2.0 * PI)).mod(24.0)
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
