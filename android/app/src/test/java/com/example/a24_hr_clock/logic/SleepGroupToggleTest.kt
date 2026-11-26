package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepGroupToggleTest {
    private val on = ClockSettings().withSleepGroup(true)
    private val off = ClockSettings().withSleepGroup(false)

    @Test fun masterOnSetsAllFour() {
        assertTrue(on.showSleep && on.showWindDown && on.showGrogginess && on.showSleepStages)
        assertTrue(on.sleepGroupAllOn)
        assertFalse(on.sleepGroupMixed)
        assertEquals(4, on.sleepGroupOnCount)
    }

    @Test fun masterOffClearsAllFour() {
        val s = on.withSleepGroup(false)
        assertFalse(s.showSleep || s.showWindDown || s.showGrogginess || s.showSleepStages)
        assertFalse(s.sleepGroupAllOn)
        assertFalse(s.sleepGroupMixed)
        assertEquals(0, s.sleepGroupOnCount)
    }

    @Test fun masterLeavesOtherSettingsAlone() {
        val base = ClockSettings(showNumbers = true, showEnergy = true, showSleepDebtText = true)
        val s = base.withSleepGroup(true)
        assertTrue(s.showNumbers && s.showEnergy && s.showSleepDebtText)
        assertEquals(base, s.withSleepGroup(false).copy(showSleep = false))
    }

    @Test fun childChangeIsIndependentAndMakesTheGroupMixed() {
        val s = on.copy(showWindDown = false)
        assertTrue(s.showSleep && s.showGrogginess && s.showSleepStages)
        assertFalse(s.showWindDown)
        assertTrue(s.sleepGroupMixed)
        assertFalse(s.sleepGroupAllOn)
        assertEquals(3, s.sleepGroupOnCount)

        val withoutStages = on.copy(showSleepStages = false)
        assertTrue(withoutStages.showSleep && withoutStages.showWindDown && withoutStages.showGrogginess)
        assertFalse(withoutStages.showSleepStages)
        assertTrue(withoutStages.sleepGroupMixed)
        assertFalse(withoutStages.sleepGroupAllOn)
        assertEquals(3, withoutStages.sleepGroupOnCount)
    }

    @Test fun oneChildOnIsMixed() {
        val s = off.copy(showGrogginess = true)
        assertTrue(s.sleepGroupMixed)
        assertEquals(1, s.sleepGroupOnCount)

        val stagesOnly = off.copy(showSleepStages = true)
        assertTrue(stagesOnly.sleepGroupMixed)
        assertEquals(1, stagesOnly.sleepGroupOnCount)
    }

    @Test fun masterFromMixedTurnsEverythingOn() {
        val mixed = off.copy(showSleep = true)
        assertTrue(mixed.withSleepGroup(!mixed.sleepGroupAllOn).sleepGroupAllOn)
    }

    @Test fun allChildrenOnByHandMakesMasterOn() {
        assertTrue(
            off.copy(showSleep = true, showWindDown = true, showGrogginess = true, showSleepStages = true)
                .sleepGroupAllOn
        )
    }

    @Test fun savedPrefsKeepTheirValuesAndMissingKeysUseDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        // An old save: sleep on, wind-down on, grogginess off, sleep stages missing (default off).
        val s = json.decodeFromString<ClockSettings>("""{"showSleep":true,"showWindDown":true,"showGrogginess":false}""")
        assertTrue(s.showSleep && s.showWindDown)
        assertFalse(s.showGrogginess)
        assertFalse(s.showSleepStages)
        assertTrue(s.sleepGroupMixed)
        assertEquals(2, s.sleepGroupOnCount)
        // An empty save falls back to the defaults (all off).
        assertEquals(0, json.decodeFromString<ClockSettings>("{}").sleepGroupOnCount)
    }
}
