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
}
