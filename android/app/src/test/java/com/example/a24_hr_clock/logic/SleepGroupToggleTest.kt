package com.example.a24_hr_clock.logic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepGroupToggleTest {
    private val on = ClockSettings().withSleepGroup(true)
    private val off = ClockSettings().withSleepGroup(false)

    @Test fun masterOnSetsAllThree() {
        assertTrue(on.showSleep && on.showWindDown && on.showGrogginess)
        assertTrue(on.sleepGroupAllOn)
        assertFalse(on.sleepGroupMixed)
    }

    @Test fun masterOffClearsAllThree() {
        val s = on.withSleepGroup(false)
        assertFalse(s.showSleep || s.showWindDown || s.showGrogginess)
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
        assertTrue(s.showSleep && s.showGrogginess)
        assertFalse(s.showWindDown)
        assertTrue(s.sleepGroupMixed)
        assertFalse(s.sleepGroupAllOn)
        assertEquals(2, s.sleepGroupOnCount)
    }

    @Test fun oneChildOnIsMixed() {
        val s = off.copy(showGrogginess = true)
        assertTrue(s.sleepGroupMixed)
        assertEquals(1, s.sleepGroupOnCount)
    }

    @Test fun masterFromMixedTurnsEverythingOn() {
        val mixed = off.copy(showSleep = true)
        assertTrue(mixed.withSleepGroup(!mixed.sleepGroupAllOn).sleepGroupAllOn)
    }

    @Test fun allChildrenOnByHandMakesMasterOn() {
        assertTrue(off.copy(showSleep = true, showWindDown = true, showGrogginess = true).sleepGroupAllOn)
    }

    @Test fun savedPrefsKeepTheirValuesAndMissingKeysUseDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        // An old save: sleep on, wind-down on, grogginess off.
        val s = json.decodeFromString<ClockSettings>("""{"showSleep":true,"showWindDown":true,"showGrogginess":false}""")
        assertTrue(s.showSleep && s.showWindDown)
        assertFalse(s.showGrogginess)
        assertTrue(s.sleepGroupMixed)
        // An empty save falls back to the defaults (all off).
        assertEquals(0, json.decodeFromString<ClockSettings>("{}").sleepGroupOnCount)
    }
}
