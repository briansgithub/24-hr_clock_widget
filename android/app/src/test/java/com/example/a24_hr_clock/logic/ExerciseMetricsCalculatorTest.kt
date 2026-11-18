package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseMetricsCalculatorTest {

    @Test
    fun testCalculateTrimpMale() {
        val hrSeries = listOf(150, 160, 170)
        val rhr = 55.0
        val maxHr = 190.0
        
        // Manual calculation for verification:
        // k = 1.92
        // hr=150: w = (150-55)/(190-55) = 95/135 = 0.7037; trimp = 0.7037 * exp(1.92 * 0.7037) = 0.7037 * 3.8617 = 2.7175
        // hr=160: w = (160-55)/(190-55) = 105/135 = 0.7778; trimp = 0.7778 * exp(1.92 * 0.7778) = 0.7778 * 4.4518 = 3.4626
        // hr=170: w = (170-55)/(190-55) = 115/135 = 0.8519; trimp = 0.8519 * exp(1.92 * 0.8519) = 0.8519 * 5.1328 = 4.3726
        // Total = 10.5527 -> Rounded to 10.55
        
        val result = ExerciseMetricsCalculator.calculateTrimp(hrSeries, rhr, maxHr, isMale = true)
        assertEquals(10.55, result, 0.01)
    }

    @Test
    fun testCalculateTrimpFemale() {
        val hrSeries = listOf(150)
        val rhr = 55.0
        val maxHr = 190.0
        
        // k = 1.67
        // hr=150: w = 0.7037; trimp = 0.7037 * exp(1.67 * 0.7037) = 0.7037 * 3.2387 = 2.279
        
        val result = ExerciseMetricsCalculator.calculateTrimp(hrSeries, rhr, maxHr, isMale = false)
        assertEquals(2.28, result, 0.01)
    }

    @Test
    fun testCalculateRelativeLoad() {
        val trimp = 100.0
        val avg = 80.0
        
        // (100 / 80) * 100 = 125.0
        val result = ExerciseMetricsCalculator.calculateRelativeLoad(trimp, avg)
        assertEquals(125.0, result, 0.1)
    }

    @Test
    fun testCalculateRelativeLoadZeroBaseline() {
        val result = ExerciseMetricsCalculator.calculateRelativeLoad(100.0, 0.0)
        assertEquals(0.0, result, 0.0)
    }
}
