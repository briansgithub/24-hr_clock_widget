package com.example.a24_hr_clock.logic

import org.junit.Assert.*
import org.junit.Test

class HealthIdentityRulesTest {
    private val me = HealthIdentity(healthUserId = "health-1", legacyUserId = "legacy-1")

    @Test fun renewalWorksInABuildWithoutTheLegacyAccount() {
        // The Pixel build had HEALTH_LEGACY_ACCOUNT = "", so every renewal failed and sleep went stale.
        assertTrue(HealthIdentityRules.renewalMatches(me, "health-1", ""))
    }

    @Test fun renewalStillChecksTheLegacyAccountWhenConfigured() {
        assertTrue(HealthIdentityRules.renewalMatches(me, "health-1", "legacy-1"))
        assertFalse(HealthIdentityRules.renewalMatches(me, "health-1", "someone-else"))
    }

    @Test fun renewalRejectsAnotherHealthUser() {
        assertFalse(HealthIdentityRules.renewalMatches(me, "health-2", ""))
        assertFalse(HealthIdentityRules.renewalMatches(me, "", ""))
    }

    @Test fun reconnectingGoogleHealthOnlyNeedsTheStoredHealthUser() {
        assertTrue(HealthIdentityRules.connectMatches(me, "google_health", "health-1", ""))
        assertFalse(HealthIdentityRules.connectMatches(me, "google_health", "health-2", ""))
    }

    @Test fun firstMoveFromFitbitNeedsAKnownLegacyAccount() {
        assertTrue(HealthIdentityRules.connectMatches(me, "fitbit", "legacy-1", ""))
        assertTrue(HealthIdentityRules.connectMatches(me, "fitbit", "", "legacy-1"))
        assertFalse(HealthIdentityRules.connectMatches(me, "fitbit", "", ""))
        assertFalse(HealthIdentityRules.connectMatches(me, "fitbit", "legacy-2", ""))
    }
}
