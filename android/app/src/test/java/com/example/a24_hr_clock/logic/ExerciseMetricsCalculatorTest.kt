package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseMetricsCalculatorTest {

    @Test
    fun testCalculateTrimp() {
        val hrSeries = listOf(150, 160, 170)
        val rhr = 55.0
        val maxHr = 190.0
        
        // Manual calculation for verification:
        // k = 1.92
        // hr=150: w = (150-55)/(190-55) = 95/135 = 0.7037; trimp = 0.7037 * exp(1.92 * 0.7037) = 0.7037 * 3.8617 = 2.7175
        // hr=160: w = (160-55)/(190-55) = 105/135 = 0.7778; trimp = 0.7778 * exp(1.92 * 0.7778) = 0.7778 * 4.4518 = 3.4626
        // hr=170: w = (170-55)/(190-55) = 115/135 = 0.8519; trimp = 0.8519 * exp(1.92 * 0.8519) = 0.8519 * 5.1328 = 4.3726
        // Total = 10.5527 -> Rounded to 10.55
        
        val result = ExerciseMetricsCalculator.calculateTrimp(hrSeries, rhr, maxHr)
        assertEquals(10.55, result, 0.01)
    }

    @Test
    fun testCalculateEpoc() {
        val hrSeries = listOf(150, 160)
        val rhr = 55.0
        
        // Manual calculation:
        // gain = 0.05, decay = 0.001
        // hr=150: intensity = 95; delta = (0.05 * 95) - (0.001 * 0) = 4.75; epoc = 4.75
        // hr=160: intensity = 105; delta = (0.05 * 105) - (0.001 * 4.75) = 5.25 - 0.00475 = 5.24525; epoc = 4.75 + 5.24525 = 9.99525
        // Total rounded = 10.0
        
        val result = ExerciseMetricsCalculator.calculateEpoc(hrSeries, rhr)
        assertEquals(10.0, result, 0.01)
    }

    @Test
    fun testCalculateHrss() {
        val trimp = 100.0
        val avg = 80.0
        
        // (100 / 80) * 100 = 125.0
        val result = ExerciseMetricsCalculator.calculateHrss(trimp, avg)
        assertEquals(125.0, result, 0.1)
    }

    @Test
    fun testGenerateSystemAlert() {
        val medicatedBase = 30.0
        
        // Depleted
        assertEquals("STATUS: DEPLETED. HRV is below medicated baseline. Strict rest recommended.", 
            ExerciseMetricsCalculator.generateSystemAlert(24.0, 50.0, medicatedBase))
            
        // Overreaching
        assertEquals("STATUS: OVERREACHING. High load detected with suppressed HRV. Focus on Zone 1 recovery.", 
            ExerciseMetricsCalculator.generateSystemAlert(29.0, 130.0, medicatedBase))
            
        // Optimized
        assertEquals("STATUS: OPTIMIZED. Nervous system is resilient today. Cleared for high intensity.", 
            ExerciseMetricsCalculator.generateSystemAlert(36.0, 80.0, medicatedBase))
            
        // Steady
        assertEquals("STATUS: STEADY. System is holding at pharmacological baseline. Proceed as planned.", 
            ExerciseMetricsCalculator.generateSystemAlert(30.0, 80.0, medicatedBase))
    }
}
