package com.example.a24_hr_clock.logic

import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.*

object EnergyCalculator {

    const val MODEL_M8 = "M8_adaptive_sleep"
    const val MODEL_M9 = "M9_awake_hybrid"
    const val PRODUCTION_MODEL_ID = GeneratedEnergyModel.MODEL_ID

    private val adaptivePersonalModelIds = setOf(
        PRODUCTION_MODEL_ID,
        MODEL_M8,
        MODEL_M9,
        // Treat persisted legacy IDs as a migration alias so existing installs
        // receive the production adaptive model without a settings reset.
        "M4_circadian_daily_baseline",
        "M7_empirical_clock_fourier"
    )
    private val adaptiveCircadianCoefs = GeneratedEnergyModel.CIRCADIAN_COEFS
    private const val adaptiveHarmonics = GeneratedEnergyModel.HARMONICS
    private const val referenceSleepHours = GeneratedEnergyModel.REFERENCE_SLEEP_HOURS
    private const val referencePeakClock = GeneratedEnergyModel.REFERENCE_PEAK_CLOCK
    private const val maxPhaseShiftHours = GeneratedEnergyModel.MAX_PHASE_SHIFT_HOURS
    private const val adaptiveTauWake = GeneratedEnergyModel.TAU_WAKE
    private const val adaptiveTauSleep = GeneratedEnergyModel.TAU_SLEEP
    private const val adaptiveInertiaTau = GeneratedEnergyModel.INERTIA_TAU
    private const val homeostaticScale = GeneratedEnergyModel.HOMEOSTATIC_SCALE
    private const val adaptiveDebtScale = GeneratedEnergyModel.DEBT_SCALE
    private const val debtSaturationHours = GeneratedEnergyModel.DEBT_SATURATION_HOURS
    private const val inertiaScale = GeneratedEnergyModel.INERTIA_SCALE
    private const val processSMin = GeneratedEnergyModel.PROCESS_S_MIN
    private const val processSMax = GeneratedEnergyModel.PROCESS_S_MAX
    private const val homeostaticDeltaMin = GeneratedEnergyModel.HOMEOSTATIC_DELTA_MIN
    private const val homeostaticDeltaMax = GeneratedEnergyModel.HOMEOSTATIC_DELTA_MAX

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

    private fun parseSleepDateTime(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value.removeSuffix("Z")) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDateTime() }.getOrNull()

    private fun clockHour(value: LocalDateTime): Double =
        value.hour + value.minute / 60.0 + value.second / 3600.0

    /** Build the renderer contract from Fitbit sessions for one sleep date. */
    fun buildSleepModelContext(
        sleepLogs: List<SleepLogEntry>,
        includeNaps: Boolean = true
    ): SleepModelContext {
        val mainSleep = sleepLogs.firstOrNull { it.isMainSleep }
            ?: return SleepModelContext(mainSleepDuration = null)
        val mainStart = parseSleepDateTime(mainSleep.startTime)
            ?: return SleepModelContext(mainSleepDuration = null)
        val mainWake = parseSleepDateTime(mainSleep.endTime)
            ?: return SleepModelContext(mainSleepDuration = null)

        val sleepMasks = mutableListOf(
            ClockSleepInterval(
                clockHour(mainStart),
                clockHour(mainWake),
                SleepIntervalKind.MAIN
            )
        )
        val modeledNaps = mutableListOf<SleepInterval>()
        for (log in sleepLogs) {
            if (log.isMainSleep) continue
            val start = parseSleepDateTime(log.startTime) ?: continue
            val end = parseSleepDateTime(log.endTime) ?: continue
            sleepMasks.add(
                ClockSleepInterval(
                    clockHour(start),
                    clockHour(end),
                    SleepIntervalKind.NAP
                )
            )
            if (!includeNaps) continue
            val startHours = Duration.between(mainWake, start).toMillis() / 3_600_000.0
            val endHours = Duration.between(mainWake, end).toMillis() / 3_600_000.0
            if (endHours <= 0.0 || startHours >= 24.0) continue
            modeledNaps.add(
                SleepInterval(
                    max(0.0, startHours),
                    min(24.0, endHours)
                )
            )
        }

        return SleepModelContext(
            mainSleepDuration = max(0.0, mainSleep.minutesAsleep / 60.0),
            allSleepIntervals = sleepMasks,
            modeledNapIntervals = normalizedNapIntervals(modeledNaps)
        )
    }

    /** Complement merged clock sleep masks, joining a wake segment across midnight. */
    fun awakeClockSegments(
        intervals: List<ClockSleepInterval>
    ): List<AwakeClockSegment> {
        val spans = mutableListOf<AwakeClockSegment>()
        for (interval in intervals) {
            val start = interval.startHour.mod(24.0)
            val end = interval.endHour.mod(24.0)
            if (abs(start - end) <= 1e-9) continue
            if (start < end) {
                spans.add(AwakeClockSegment(start, end))
            } else {
                spans.add(AwakeClockSegment(start, 24.0))
                spans.add(AwakeClockSegment(0.0, end))
            }
        }
        if (spans.isEmpty()) return listOf(AwakeClockSegment(0.0, 24.0))

        val merged = mutableListOf<AwakeClockSegment>()
        for (span in spans.sortedBy { it.startHour }) {
            val last = merged.lastOrNull()
            if (last != null && span.startHour <= last.endHour + 1e-9) {
                merged[merged.lastIndex] = AwakeClockSegment(
                    last.startHour,
                    max(last.endHour, span.endHour)
                )
            } else {
                merged.add(span)
            }
        }

        val gaps = mutableListOf<AwakeClockSegment>()
        var cursor = 0.0
        for (span in merged) {
            if (span.startHour > cursor + 1e-9) {
                gaps.add(AwakeClockSegment(cursor, span.startHour))
            }
            cursor = max(cursor, span.endHour)
        }
        if (cursor < 24.0 - 1e-9) gaps.add(AwakeClockSegment(cursor, 24.0))

        if (
            gaps.size > 1 && gaps.first().startHour <= 1e-9 &&
            gaps.last().endHour >= 24.0 - 1e-9
        ) {
            val wrapped = AwakeClockSegment(
                gaps.last().startHour,
                gaps.first().endHour + 24.0
            )
            return gaps.subList(1, gaps.lastIndex) + wrapped
        }
        return gaps
    }

    fun awakeEnergyRange(values: Iterable<Double?>): AwakeEnergyRange? {
        val awake = values.filterNotNull()
        if (awake.isEmpty()) return null
        return AwakeEnergyRange(
            minimum = awake.minOrNull()!!,
            maximum = awake.maxOrNull()!!
        )
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
        var baseline = adaptiveCircadianCoefs[0]
        for (harmonic in 1..adaptiveHarmonics) {
            val coefficientIndex = 1 + (harmonic - 1) * 2
            baseline += adaptiveCircadianCoefs[coefficientIndex] * cos(harmonic * theta)
            baseline += adaptiveCircadianCoefs[coefficientIndex + 1] * sin(harmonic * theta)
        }

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

    /** Renderer-facing API: subjective energy exists only while awake. */
    fun predictEnergy(
        hClock: Double,
        wakeHour: Double,
        sleepContext: SleepModelContext?,
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
        circOffset: Double = 4.9
    ): EnergyPrediction {
        if (sleepContext == null || !sleepContext.available) {
            return EnergyPrediction(EnergyPredictionState.UNAVAILABLE)
        }
        if (sleepContext.allSleepIntervals.any { it.contains(hClock) }) {
            return EnergyPrediction(EnergyPredictionState.ASLEEP)
        }
        return EnergyPrediction(
            EnergyPredictionState.AWAKE,
            getEnergyLevel(
                hClock, wakeHour, sleepDebtHours, sleepDuration,
                bathyphaseHour, clamp, tauWake, tauSleep, tauInertia,
                debtFactor, circadianPeakOffset, sleepEfficiency,
                usePersonalModel, personalModelId, baselineIntercept,
                baselineSleepCoef, baselineDebtCoef, baselineEfficiencyCoef,
                circAmp, circAmp2, circOffset,
                sleepContext.modeledNapIntervals
            )
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

    fun predictEnergyFromSettings(
        hClock: Double,
        wakeHour: Double,
        sleepDebtHours: Double,
        sleepDuration: Double,
        bathyphaseHour: Double?,
        settings: ModelSettings,
        useBathyphase: Boolean,
        sleepContext: SleepModelContext?,
        clamp: Boolean = false,
        sleepEfficiency: Double = 0.9
    ): EnergyPrediction = predictEnergy(
        hClock = hClock,
        wakeHour = wakeHour,
        sleepContext = sleepContext,
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
        circOffset = settings.circOffset
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

enum class SleepIntervalKind { MAIN, NAP }

data class ClockSleepInterval(
    val startHour: Double,
    val endHour: Double,
    val kind: SleepIntervalKind
) {
    /** Half-open [start, end) membership on a circular 24-hour clock. */
    fun contains(clockHour: Double): Boolean {
        val start = startHour.mod(24.0)
        val end = endHour.mod(24.0)
        val hour = clockHour.mod(24.0)
        if (abs(start - end) <= 1e-9) return false
        return if (start < end) hour >= start && hour < end
        else hour >= start || hour < end
    }
}

data class SleepModelContext(
    val mainSleepDuration: Double?,
    val allSleepIntervals: List<ClockSleepInterval> = emptyList(),
    val modeledNapIntervals: List<SleepInterval> = emptyList()
) {
    val available: Boolean
        get() = mainSleepDuration != null &&
            allSleepIntervals.any { it.kind == SleepIntervalKind.MAIN }
}

enum class EnergyPredictionState { AWAKE, ASLEEP, UNAVAILABLE }

data class EnergyPrediction(
    val state: EnergyPredictionState,
    val value: Double? = null
) {
    init {
        require((state == EnergyPredictionState.AWAKE) == (value != null)) {
            "Only awake energy predictions may contain a value"
        }
    }
}

data class AwakeClockSegment(
    val startHour: Double,
    val endHour: Double
)

data class AwakeEnergyRange(
    val minimum: Double,
    val maximum: Double
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
