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
        
        // Scenario B: Today didn't exist in the calculation loop (same as before midnight)
        // With the NEW logic, if today is excluded, yesterday's weight is 0.9^0 = 1.0.
        // Yesterday's debt = 8.0 - 7.0 = 1.0.
        // HOWEVER, the loop goes up to 15 days.
        // All days from T-1 to T-14 will be included if not in logs.
        // T-1 (Yesterday): actual=7.0, debt=1.0, weight=0.9^0=1.0 -> 1.0
        // T-2: actual=0.0, debt=8.0, weight=0.9^1=0.9 -> 7.2
        // T-3: actual=0.0, debt=8.0, weight=0.9^2=0.81 -> 6.48
        // ... and so on. 
        // So the total debt is NOT 1.0, it's 1.0 + (rest of 13 days of full debt).
        
        // Let's check what the value ACTUALLY is by adding a print or adjusting expectation.
        // Actually, we want to verify STABILITY.
        // So let's compare "Today Excluded" with "Yesterday's calculation (simulated)".
        
        // Pre-Midnight (Yesterday was T-0)
        // We can't easily change LocalDate.now() without mocking or passing it as param.
        // But we can check if it matches the manually calculated expected value.
        // 1.0 * (0.9^0) + 8.0 * (0.9^1) + 8.0 * (0.9^2) ... 8.0 * (0.9^13)
        var expected = 1.0
        for (w in 1..13) {
            expected += 8.0 * Math.pow(0.9, w.toDouble())
        }
        
        assertEquals("Debt should match manually calculated stable debt", expected, debtWithExcludedToday, 0.001)
        
        // Scenario C: Today is NOT excluded and has no sleep log yet.
        val debtWithIncludedToday = EnergyCalculator.computeSleepDebt(
            sleepLogs = logs,
            sleepNeedHours = sleepNeed,
            excludedDates = emptyList()
        )
        
        // Today's debt = 8.0 - 0.0 = 8.0.
        // Total debt = 8.0 * (0.9^0) + 1.0 * (0.9^1) + 8.0 * (0.9^2) ... 8.0 * (0.9^14)
        var expectedIncluded = 8.0 * Math.pow(0.9, 0.0) + 1.0 * Math.pow(0.9, 1.0)
        for (w in 2..14) {
            expectedIncluded += 8.0 * Math.pow(0.9, w.toDouble())
        }
        
        assertEquals("Debt should increase when today is included but has no logs", expectedIncluded, debtWithIncludedToday, 0.001)
    }
}
