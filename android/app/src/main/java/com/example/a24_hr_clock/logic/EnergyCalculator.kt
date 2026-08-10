package com.example.a24_hr_clock.logic

import kotlin.math.*

object EnergyCalculator {

    const val MODEL_M8 = "M8_adaptive_sleep"

    private val adaptivePersonalModelIds = setOf(
        MODEL_M8,
        // Treat persisted legacy IDs as a migration alias so existing installs
        // receive M8 without requiring a settings reset.
        "M4_circadian_daily_baseline",
        "M7_empirical_clock_fourier"
    )
    private val adaptiveCircadianCoefs = doubleArrayOf(
        51.79829899798624,
        -8.970119863421589,
        -19.40441654693655,
        4.839958739091168,
        -5.463160275994447
    )
    private const val referenceSleepHours = 8.0
    private const val referencePeakClock = 13.48
    private const val maxPhaseShiftHours = 3.0
    private const val adaptiveTauWake = 23.0
    private const val adaptiveTauSleep = 4.0
    private const val adaptiveInertiaTau = 0.75
    private const val homeostaticScale = 25.0
    private const val adaptiveDebtScale = 2.0
    private const val debtSaturationHours = 12.0
    private const val inertiaScale = 12.0
    private const val processSMin = 0.05
    private const val processSMax = 1.0
    private const val homeostaticDeltaMin = -0.15
    private const val homeostaticDeltaMax = 0.35

    private data class ProcessSContext(
        val delta: Double,
        val hoursSinceLastWake: Double,
        val asleep: Boolean
    )

    private fun processSAfterSleep(sleepDuration: Double): Double {
        val duration = sleepDuration.coerceIn(0.0, 24.0)
        val awakeDuration = 24.0 - duration
        val eWake = exp(-awakeDuration / adaptiveTauWake)
        val eSleep = exp(-duration / adaptiveTauSleep)
        val divisor = max(0.0001, 1.0 - eWake * eSleep)
        val state = (
            processSMax * eSleep * (1.0 - eWake) +
                processSMin * (1.0 - eSleep)
            ) / divisor
        return state.coerceIn(processSMin, processSMax)
    }

    private fun processSAwake(state: Double, hours: Double): Double =
        state + (processSMax - state) *
            (1.0 - exp(-max(0.0, hours) / adaptiveTauWake))

    private fun processSAsleep(state: Double, hours: Double): Double =
        processSMin + (state - processSMin) *
            exp(-max(0.0, hours) / adaptiveTauSleep)

    private fun normalizedNapIntervals(intervals: List<SleepInterval>): List<SleepInterval> {
        val ordered = intervals.mapNotNull { interval ->
            if (!interval.startAfterWake.isFinite() || !interval.endAfterWake.isFinite()) {
                null
            } else {
                val start = interval.startAfterWake.coerceIn(0.0, 24.0)
                val end = interval.endAfterWake.coerceIn(0.0, 24.0)
                if (end > start) SleepInterval(start, end) else null
            }
        }.sortedBy { it.startAfterWake }

        val merged = mutableListOf<SleepInterval>()
        for (interval in ordered) {
            val last = merged.lastOrNull()
            if (last != null && interval.startAfterWake <= last.endAfterWake) {
                merged[merged.lastIndex] = SleepInterval(
                    last.startAfterWake,
                    max(last.endAfterWake, interval.endAfterWake)
                )
            } else {
                merged.add(interval)
            }
        }
        return merged
    }

    private fun adaptiveProcessSContext(
        hoursSinceMainWake: Double,
        sleepDuration: Double,
        napIntervals: List<SleepInterval>
    ): ProcessSContext {
        var actual = processSAfterSleep(sleepDuration)
        var rested = processSAfterSleep(referenceSleepHours)
        val target = hoursSinceMainWake.coerceIn(0.0, 24.0)
        var cursor = 0.0
        var lastWake = 0.0

        for (nap in normalizedNapIntervals(napIntervals)) {
            if (nap.startAfterWake >= target) break
            val awakeHours = max(0.0, nap.startAfterWake - cursor)
            actual = processSAwake(actual, awakeHours)
            rested = processSAwake(rested, awakeHours)

            val sleepEnd = min(target, nap.endAfterWake)
            actual = processSAsleep(actual, sleepEnd - nap.startAfterWake)
            rested = processSAsleep(rested, sleepEnd - nap.startAfterWake)
            if (target < nap.endAfterWake) {
                return ProcessSContext(actual - rested, 0.0, true)
            }
            cursor = nap.endAfterWake
            lastWake = nap.endAfterWake
        }

        val awakeHours = max(0.0, target - cursor)
        actual = processSAwake(actual, awakeHours)
        rested = processSAwake(rested, awakeHours)
        return ProcessSContext(actual - rested, max(0.0, target - lastWake), false)
    }

    private fun circularHourDifference(target: Double, reference: Double): Double =
        (target - reference + 12.0).mod(24.0) - 12.0

    /**
     * M8: personal local-clock Fourier baseline plus continuous, nap-aware
     * Process S, saturating observed-night debt, and wake/nap sleep inertia.
     */
    fun adaptiveSleepEnergy(
        clockHour: Double,
        wakeHour: Double,
        sleepDuration: Double,
        sleepDebtHours: Double,
        bathyphaseHour: Double? = null,
        circadianPeakOffset: Double? = null,
        napIntervals: List<SleepInterval> = emptyList(),
        clamp: Boolean = true
    ): Double {
        val t = (clockHour - wakeHour).mod(24.0)
        val process = adaptiveProcessSContext(t, sleepDuration, napIntervals)
        if (process.asleep) return 0.0

        val desiredPeak = when {
            bathyphaseHour != null -> (bathyphaseHour + 15.0).mod(24.0)
            circadianPeakOffset != null -> (wakeHour + circadianPeakOffset).mod(24.0)
            else -> null
        }
        val phaseShift = desiredPeak?.let {
            circularHourDifference(it, referencePeakClock)
                .coerceIn(-maxPhaseShiftHours, maxPhaseShiftHours)
        } ?: 0.0

        val theta = 2.0 * PI * (clockHour - phaseShift).mod(24.0) / 24.0
        val baseline = adaptiveCircadianCoefs[0] +
            adaptiveCircadianCoefs[1] * cos(theta) +
            adaptiveCircadianCoefs[2] * sin(theta) +
            adaptiveCircadianCoefs[3] * cos(2.0 * theta) +
            adaptiveCircadianCoefs[4] * sin(2.0 * theta)

        val processDelta = process.delta.coerceIn(homeostaticDeltaMin, homeostaticDeltaMax)
        val debtLoad = 1.0 - exp(-max(0.0, sleepDebtHours) / debtSaturationHours)
        val inertiaLoad = exp(-process.hoursSinceLastWake / adaptiveInertiaTau)
        val energyPct = baseline -
            homeostaticScale * processDelta -
            adaptiveDebtScale * debtLoad -
            inertiaScale * inertiaLoad
        val alertness = energyPct / 100.0
        return if (clamp) alertness.coerceIn(0.0, 1.0) else alertness
    }

    fun personalM4Energy(
        t: Double,
        sleepDuration: Double,
        sleepDebtHours: Double,
        sleepEfficiency: Double = 0.9,
        baselineIntercept: Double = 37.3,
        baselineSleepCoef: Double = 0.04,
        baselineDebtCoef: Double = 0.45,
        baselineEfficiencyCoef: Double = -0.8,
        circAmp: Double = 20.0,
        circAmp2: Double = 3.3,
        circOffset: Double = 4.9,
        clamp: Boolean = true
    ): Double {
        val baseline = baselineIntercept +
            baselineSleepCoef * sleepDuration +
            baselineDebtCoef * sleepDebtHours +
            baselineEfficiencyCoef * sleepEfficiency
        val shape = circAmp * cos((PI / 12.0) * (t - circOffset)) +
            circAmp2 * cos((PI / 6.0) * (t - 8.0))
        val energyPct = (baseline + shape).coerceIn(0.0, 100.0)
        val alertness = energyPct / 100.0
        return if (clamp) alertness.coerceIn(0.0, 1.0) else alertness
    }

    fun twoProcessEnergy(
        t: Double,
        sleepDebtHours: Double = 0.0,
        sleepDuration: Double = 7.5,
        circadianPeakOffset: Double = 10.0,
        clamp: Boolean = true,
        tauWake: Double = 23.0,
        tauSleep: Double = 4.0,
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
        tauWake: Double = 23.0,
        tauSleep: Double = 4.0,
        tauInertia: Double = 1.5,
        debtFactor: Double = 1.0,
        circadianPeakOffset: Double? = null,
        sleepEfficiency: Double = 0.9,
        usePersonalModel: Boolean = false,
        personalModelId: String = "",
        baselineIntercept: Double = 37.3,
        baselineSleepCoef: Double = 0.04,
        baselineDebtCoef: Double = 0.45,
        baselineEfficiencyCoef: Double = -0.8,
        circAmp: Double = 20.0,
        circAmp2: Double = 3.3,
        circOffset: Double = 4.9,
        napIntervals: List<SleepInterval> = emptyList()
    ): Double {
        val t = (hClock - wakeHour).mod(24.0)

        if (usePersonalModel && personalModelId in adaptivePersonalModelIds) {
            return adaptiveSleepEnergy(
                clockHour = hClock,
                wakeHour = wakeHour,
                sleepDuration = sleepDuration,
                sleepDebtHours = sleepDebtHours,
                bathyphaseHour = bathyphaseHour,
                circadianPeakOffset = circadianPeakOffset,
                napIntervals = napIntervals,
                clamp = clamp
            )
        }

        // Calculate peak_offset
        val peakOffset = if (circadianPeakOffset != null) {
            circadianPeakOffset
        } else if (bathyphaseHour != null) {
            // WMZ peak is roughly 15 hours after bathyphase
            val peakH = (bathyphaseHour + 15.0).mod(24.0)
            (peakH - wakeHour).mod(24.0)
        } else {
            13.0 // sensible population-average fallback (15h post-bathy, assuming 2h pre-wake nadir)
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
        var debt = 0.0
        val decayFactor = 0.9
        val excluded = excludedDates.toSet()

        // Oldest to newest: update only when an observed night exists.
        for (i in 14 downTo 0) {
            val dateStr = today.minusDays(i.toLong()).toString()
            if (dateStr in excluded) continue
            val actual = dailySleep[dateStr] ?: continue
            val deficit = sleepNeedHours - actual
            debt = max(0.0, debt * decayFactor + deficit)
        }

        return debt
    }

    /**
     * Convenience wrapper that passes personal-model fields from [ModelSettings].
     */
    fun getEnergyLevelFromSettings(
        hClock: Double,
        wakeHour: Double,
        sleepDebtHours: Double,
        sleepDuration: Double,
        bathyphaseHour: Double?,
        settings: ModelSettings,
        useBathyphase: Boolean,
        clamp: Boolean = false,
        sleepEfficiency: Double = 0.9,
        napIntervals: List<SleepInterval> = emptyList()
    ): Double = getEnergyLevel(
        hClock = hClock,
        wakeHour = wakeHour,
        sleepDebtHours = sleepDebtHours,
        sleepDuration = sleepDuration,
        bathyphaseHour = if (useBathyphase) bathyphaseHour else null,
        clamp = clamp,
        tauWake = settings.tauWake,
        tauSleep = settings.tauSleep,
        tauInertia = settings.tauInertia,
        debtFactor = settings.debtFactor,
        circadianPeakOffset = if (useBathyphase) null else settings.circadianOffset,
        sleepEfficiency = sleepEfficiency,
        usePersonalModel = settings.usePersonalModel,
        personalModelId = settings.personalModelId,
        baselineIntercept = settings.baselineIntercept,
        baselineSleepCoef = settings.baselineSleepCoef,
        baselineDebtCoef = settings.baselineDebtCoef,
        baselineEfficiencyCoef = settings.baselineEfficiencyCoef,
        circAmp = settings.circAmp,
        circAmp2 = settings.circAmp2,
        circOffset = settings.circOffset,
        napIntervals = napIntervals
    )

    fun findBathyphase(intradayHr: List<HeartRatePoint>): Double? {
        if (intradayHr.isEmpty()) return null

        val buckets = mutableMapOf<Int, MutableList<Double>>()
        for (p in intradayHr) {
            val h = p.time.split(":").firstOrNull()?.toIntOrNull() ?: continue
            buckets.getOrPut(h) { mutableListOf() }.add(p.value.toDouble())
        }

        if (buckets.isEmpty()) return null

        val avgByHour = buckets.mapValues { it.value.average() }
        val minHour = avgByHour.minByOrNull { it.value }?.key ?: return null

        // Parabolic Fit for sub-hour precision
        val y2 = avgByHour[minHour] ?: return minHour.toDouble()
        val prevHour = (minHour - 1).mod(24)
        val nextHour = (minHour + 1).mod(24)

        val y1 = avgByHour[prevHour]
        val y3 = avgByHour[nextHour]

        if (y1 != null && y3 != null) {
            // Vertex of parabola passing through (x1, y1), (x2, y2), (x3, y3)
            // where x2 = x1 + 1, x3 = x2 + 1.
            // Formula: x = x2 - 0.5 * (y3 - y1) / (y1 - 2*y2 + y3)
            val denom = y1 - 2 * y2 + y3
            if (abs(denom) > 0.0001) {
                val offset = -0.5 * (y3 - y1) / denom
                // Only apply if the offset is within [-1, 1] range to keep it sane
                if (abs(offset) <= 1.0) {
                    return (minHour.toDouble() + offset).mod(24.0)
                }
            }
        }

        return minHour.toDouble()
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

data class SleepInterval(
    val startAfterWake: Double,
    val endAfterWake: Double
)

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
