package com.example.a24_hr_clock.logic

import org.junit.Assert.*
import org.junit.Test

class EnergyCalculatorTest {

    @Test
    fun testEnergyRange() {
        val wakeHour = 7.0
        val sleepDuration = 8.0
        val sleepDebt = 5.0
        
        for (h in 0..23) {
            val energy = EnergyCalculator.getEnergyLevel(
                hClock = h.toDouble(),
                wakeHour = wakeHour,
                sleepDebtHours = sleepDebt,
                sleepDuration = sleepDuration
            )
            assertTrue("Energy at hour $h should be between 0 and 1, but was $energy", energy in 0.0..1.0)
        }
    }

    @Test
    fun testWakeUpTrend() {
        val wakeHour = 7.0
        // Immediately after wake (7.0), energy should be lower due to sleep inertia
        val energyAtWake = EnergyCalculator.getEnergyLevel(7.0, wakeHour)
        // A few hours later (e.g. 10.0), energy should be higher
        val energyLater = EnergyCalculator.getEnergyLevel(10.0, wakeHour)
        
        assertTrue("Energy should increase after waking up (inertia clearing). Wake: $energyAtWake, Later: $energyLater", energyLater > energyAtWake)
    }

    @Test
    fun testSleepDebtStabilityWhenTodayExcluded() {
        val today = java.time.LocalDate.now().toString()
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        
        val logs = listOf(
            SleepLog(yesterday, 420, true, 480) // 7h sleep yesterday
        )
        val sleepNeed = 8.0
        
        // Scenario A: Today is excluded (simulating midnight just passed)
        val debtWithExcludedToday = EnergyCalculator.computeSleepDebt(
            sleepLogs = logs,
            sleepNeedHours = sleepNeed,
            excludedDates = listOf(today)
        )
        
        // Anchored Logic: Today (T-0) is skipped. Yesterday (T-1) has weight 0.9^1.
        // Yesterday's debt = 8.0 - 7.0 = 1.0.
        // T-1 (Yesterday): actual=7.0, debt=1.0, weight=0.9^1=0.9 -> 0.9
        // T-2: actual=0.0, debt=8.0, weight=0.9^2=0.81 -> 6.48
        // ... and so on up to T-14.
        
        var expected = 0.0
        for (i in 1 until 15) {
            val actual = if (i == 1) 7.0 else 0.0
            val nightlyDebt = sleepNeed - actual
            expected += nightlyDebt * Math.pow(0.9, i.toDouble())
        }
        
        assertEquals("Debt should match anchored calendar offset calculation", expected, debtWithExcludedToday, 0.001)
        
        // Scenario C: Today is NOT excluded and has no sleep log yet.
        val debtWithIncludedToday = EnergyCalculator.computeSleepDebt(
            sleepLogs = logs,
            sleepNeedHours = sleepNeed,
            excludedDates = emptyList()
        )
        
        // Today's debt = 8.0 - 0.0 = 8.0.
        // Total debt = 8.0 * (0.9^0) + 1.0 * (0.9^1) + 8.0 * (0.9^2) ... 8.0 * (0.9^14)
        var expectedIncluded = 0.0
        for (i in 0 until 15) {
            val actual = if (i == 1) 7.0 else 0.0
            val nightlyDebt = sleepNeed - actual
            expectedIncluded += nightlyDebt * Math.pow(0.9, i.toDouble())
        }
        
        assertEquals("Debt should increase when today is included but has no logs", expectedIncluded, debtWithIncludedToday, 0.001)
    }
}
