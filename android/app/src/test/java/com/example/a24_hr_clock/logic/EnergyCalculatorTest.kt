package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyCalculatorTest {

    @Test
    fun adaptiveEnergyIsBoundedAcrossDay() {
        for (halfHour in 0 until 48) {
            val energy = EnergyCalculator.adaptiveSleepEnergy(
                clockHour = halfHour / 2.0,
                wakeHour = 8.0,
                sleepDuration = 6.5,
                sleepDebtHours = 8.0,
                napIntervals = listOf(SleepInterval(5.0, 5.5))
            )
            assertTrue("M8 energy should stay in 0..1, was $energy", energy in 0.0..1.0)
        }
    }

    @Test
    fun shorterSleepAndMoreDebtDoNotRaiseEnergy() {
        val rested = EnergyCalculator.adaptiveSleepEnergy(18.0, 8.0, 8.0, 0.0)
        val shortSleep = EnergyCalculator.adaptiveSleepEnergy(18.0, 8.0, 5.0, 0.0)
        val highDebt = EnergyCalculator.adaptiveSleepEnergy(18.0, 8.0, 8.0, 20.0)
        assertTrue(shortSleep < rested)
        assertTrue(highDebt < rested)
    }

    @Test
    fun napRecoveryAndInertiaAreTimeLocal() {
        val nap = listOf(SleepInterval(4.0, 4.5))
        val noNapImmediate = EnergyCalculator.adaptiveSleepEnergy(12.5, 8.0, 5.5, 0.0)
        val withNapImmediate = EnergyCalculator.adaptiveSleepEnergy(
            12.5, 8.0, 5.5, 0.0, napIntervals = nap
        )
        val noNapLater = EnergyCalculator.adaptiveSleepEnergy(18.0, 8.0, 5.5, 0.0)
        val withNapLater = EnergyCalculator.adaptiveSleepEnergy(
            18.0, 8.0, 5.5, 0.0, napIntervals = nap
        )
        assertTrue(withNapImmediate < noNapImmediate)
        assertTrue(withNapLater > noNapLater)
    }

    private fun sleepLog(
        isMain: Boolean,
        start: String,
        end: String,
        minutes: Int
    ) = SleepLogEntry(
        dateOfSleep = "2026-07-21",
        minutesAsleep = minutes,
        isMainSleep = isMain,
        timeInBed = minutes + 20,
        startTime = start,
        endTime = end
    )

    @Test
    fun predictionStatesUseHalfOpenSleepBoundaries() {
        val context = EnergyCalculator.buildSleepModelContext(
            listOf(
                sleepLog(true, "2026-07-20T23:00:00", "2026-07-21T07:00:00", 450),
                sleepLog(false, "2026-07-21T14:00:00", "2026-07-21T14:30:00", 25)
            ),
            includeNaps = true
        )
        fun prediction(hour: Double) = EnergyCalculator.predictEnergy(
            hClock = hour,
            wakeHour = 7.0,
            sleepContext = context,
            sleepDuration = 7.5
        )

        assertEquals(EnergyPredictionState.ASLEEP, prediction(23.0).state)
        assertEquals(EnergyPredictionState.ASLEEP, prediction(1.0).state)
        assertEquals(EnergyPredictionState.AWAKE, prediction(7.0).state)
        assertNotNull(prediction(7.0).value)
        assertEquals(EnergyPredictionState.ASLEEP, prediction(14.0).state)
        assertEquals(EnergyPredictionState.AWAKE, prediction(14.5).state)
    }

    @Test
    fun disablingNapEffectsStillMasksNap() {
        val logs = listOf(
            sleepLog(true, "2026-07-21T00:00:00", "2026-07-21T08:00:00", 450),
            sleepLog(false, "2026-07-21T12:00:00", "2026-07-21T12:30:00", 25)
        )
        val withEffects = EnergyCalculator.buildSleepModelContext(logs, includeNaps = true)
        val withoutEffects = EnergyCalculator.buildSleepModelContext(logs, includeNaps = false)
        fun prediction(hour: Double, context: SleepModelContext) =
            EnergyCalculator.predictEnergy(
                hClock = hour,
                wakeHour = 8.0,
                sleepContext = context,
                sleepDuration = 7.5,
                usePersonalModel = true,
                personalModelId = EnergyCalculator.MODEL_M8
            )

        assertEquals(EnergyPredictionState.ASLEEP, prediction(12.25, withEffects).state)
        assertEquals(EnergyPredictionState.ASLEEP, prediction(12.25, withoutEffects).state)
        assertTrue(
            prediction(12.5, withEffects).value!! <
                prediction(12.5, withoutEffects).value!!
        )
    }

    @Test
    fun missingMainSleepIsUnavailable() {
        val context = EnergyCalculator.buildSleepModelContext(emptyList())
        val prediction = EnergyCalculator.predictEnergy(12.0, 8.0, context)
        assertEquals(EnergyPredictionState.UNAVAILABLE, prediction.state)
        assertNull(prediction.value)
    }

    @Test
    fun awakeSegmentsDoNotCrossShortSleep() {
        val intervals = listOf(
            ClockSleepInterval(23.0, 7.0, SleepIntervalKind.MAIN),
            ClockSleepInterval(14.1, 14.15, SleepIntervalKind.NAP)
        )
        val segments = EnergyCalculator.awakeClockSegments(intervals)
        assertEquals(
            listOf(AwakeClockSegment(7.0, 14.1), AwakeClockSegment(14.15, 23.0)),
            segments
        )
        for (segment in segments) {
            val midpoint = ((segment.startHour + segment.endHour) / 2.0).mod(24.0)
            assertFalse(intervals.any { it.contains(midpoint) })
        }
    }

    @Test
    fun awakeRangeIgnoresMissingValues() {
        val range = EnergyCalculator.awakeEnergyRange(listOf(null, 0.3, null, 0.8))
        assertEquals(0.3, range!!.minimum, 0.0)
        assertEquals(0.8, range.maximum, 0.0)
        assertNull(EnergyCalculator.awakeEnergyRange(listOf(null, null)))
    }

    @Test
    fun settingsUseProductionModelAndMigrateLegacyPersonalIds() {
        val m8 = ModelSettings(usePersonalModel = true)
        assertEquals(EnergyCalculator.PRODUCTION_MODEL_ID, m8.personalModelId)
        val expected = EnergyCalculator.adaptiveSleepEnergy(14.0, 9.0, 7.0, 3.0)
        val actual = EnergyCalculator.getEnergyLevelFromSettings(
            hClock = 14.0,
            wakeHour = 9.0,
            sleepDebtHours = 3.0,
            sleepDuration = 7.0,
            bathyphaseHour = null,
            settings = m8,
            useBathyphase = true,
            clamp = true
        )
        assertEquals(expected, actual, 1e-12)

        val legacy = m8.copy(personalModelId = "M4_circadian_daily_baseline")
        val migrated = EnergyCalculator.getEnergyLevelFromSettings(
            14.0, 9.0, 3.0, 7.0, null, legacy, true, true
        )
        assertEquals(expected, migrated, 1e-12)
    }

    @Test
    fun missingDatesDoNotCreateZeroSleepDeficits() {
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val logs = listOf(SleepLog(yesterday, 420, true, 480))
        assertEquals(1.0, EnergyCalculator.computeSleepDebt(logs, 8.0), 1e-12)
    }

    @Test
    fun observedOversleepRepaysPriorDebt() {
        val today = java.time.LocalDate.now()
        val logs = listOf(
            SleepLog(today.minusDays(2).toString(), 360, true, 420),
            SleepLog(today.minusDays(1).toString(), 540, true, 570)
        )
        assertEquals(0.8, EnergyCalculator.computeSleepDebt(logs, 8.0), 1e-12)
    }

    @Test
    fun sharedPythonReferenceVectors() {
        val cases = listOf(
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(14.0, 9.0, 7.0, 3.0),
                0.6599810557700918
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(
                    20.0, 9.0, 8.0, 0.0, bathyphaseHour = 5.0
                ),
                0.6594005145987748
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(
                    18.0, 8.0, 5.5, 8.0,
                    circadianPeakOffset = 12.0,
                    napIntervals = listOf(SleepInterval(4.0, 4.5))
                ),
                0.6424203612753479
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(8.0, 8.0, 8.0, 0.0),
                0.2978989746884647
            )
        )
        for ((actual, expected) in cases) {
            assertEquals(expected, actual, 1e-12)
        }
    }
}
