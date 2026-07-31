package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
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
        val asleep = EnergyCalculator.adaptiveSleepEnergy(
            12.25, 8.0, 5.5, 0.0, napIntervals = nap
        )
        assertTrue(withNapImmediate < noNapImmediate)
        assertTrue(withNapLater > noNapLater)
        assertEquals(0.0, asleep, 0.0)
    }

    @Test
    fun settingsUseM8AndMigrateLegacyPersonalIds() {
        val m8 = ModelSettings(usePersonalModel = true)
        assertEquals(EnergyCalculator.MODEL_M8, m8.personalModelId)
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
                0.6464512909036823
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(
                    20.0, 9.0, 8.0, 0.0, bathyphaseHour = 5.0
                ),
                0.6801698896361507
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(
                    18.0, 8.0, 5.5, 8.0,
                    circadianPeakOffset = 12.0,
                    napIntervals = listOf(SleepInterval(4.0, 4.5))
                ),
                0.6397337294750542
            ),
            Pair(
                EnergyCalculator.adaptiveSleepEnergy(8.0, 8.0, 8.0, 0.0),
                0.28325070000118563
            )
        )
        for ((actual, expected) in cases) {
            assertEquals(expected, actual, 1e-12)
        }
    }
}
