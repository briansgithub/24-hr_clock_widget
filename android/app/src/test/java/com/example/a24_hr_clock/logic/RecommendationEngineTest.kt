package com.example.a24_hr_clock.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecommendationEngineTest {

    private val today = LocalDate.now()
    private val defaultGoals = WeeklyGoals()

    @Test
    fun testRecoverStateDueToIllness() {
        val checkIn = CheckIn(date = today.toString(), symptoms = "Feeling ill and weak")
        val rec = RecommendationEngine.generateRecommendation(
            date = today,
            hrv7DayAvg = 40.0,
            hrvBaseline = 40.0,
            rhr7DayAvg = 60.0,
            rhrBaseline = 60.0,
            sleep7DayAvg = 8.0,
            sleepNeed = 8.0,
            recentLoad = 100.0,
            trainingGoal = defaultGoals,
            checkIn = checkIn,
            dataQuality = emptyMap()
        )
        assertEquals(RecommendationState.RECOVER, rec.state)
        assertEquals("Focus on recovery", rec.headline)
    }

    @Test
    fun testRecoverStateDueToLowHRV() {
        val rec = RecommendationEngine.generateRecommendation(
            date = today,
            hrv7DayAvg = 30.0, // 75% of baseline
            hrvBaseline = 40.0,
            rhr7DayAvg = 60.0,
            rhrBaseline = 60.0,
            sleep7DayAvg = 8.0,
            sleepNeed = 8.0,
            recentLoad = 100.0,
            trainingGoal = defaultGoals,
            checkIn = null,
            dataQuality = emptyMap()
        )
        assertEquals(RecommendationState.RECOVER, rec.state)
        assert(rec.reasons.any { it.contains("HRV trend is below") })
    }

    @Test
    fun testBuildStateFavorableSignals() {
        val rec = RecommendationEngine.generateRecommendation(
            date = today,
            hrv7DayAvg = 45.0, // 112% of baseline
            hrvBaseline = 40.0,
            rhr7DayAvg = 58.0,
            rhrBaseline = 60.0,
            sleep7DayAvg = 8.5,
            sleepNeed = 8.0,
            recentLoad = 90.0,
            trainingGoal = defaultGoals,
            checkIn = null,
            dataQuality = emptyMap()
        )
        assertEquals(RecommendationState.BUILD, rec.state)
    }

    @Test
    fun testInsufficientData() {
        val rec = RecommendationEngine.generateRecommendation(
            date = today,
            hrv7DayAvg = null,
            hrvBaseline = 40.0,
            rhr7DayAvg = 60.0,
            rhrBaseline = 60.0,
            sleep7DayAvg = 8.0,
            sleepNeed = 8.0,
            recentLoad = 100.0,
            trainingGoal = defaultGoals,
            checkIn = null,
            dataQuality = emptyMap()
        )
        assertEquals(RecommendationState.INSUFFICIENT_DATA, rec.state)
        assertEquals(Confidence.LOW, rec.confidence)
    }
}
