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

    @Test fun connectMismatchSaysWhyAConnectWasRefused() {
        assertNull(HealthIdentityRules.connectMismatch(me, "fitbit", "", "legacy-1"))
        assertEquals("This build has no HEALTH_LEGACY_ACCOUNT to check the account against",
            HealthIdentityRules.connectMismatch(me, "fitbit", "", ""))
        assertEquals("The Google account is not the Fitbit account this app was set up with",
            HealthIdentityRules.connectMismatch(me, "fitbit", "", "legacy-2"))
        assertEquals("A different Google Health user signed in",
            HealthIdentityRules.connectMismatch(me, "google_health", "health-2", ""))
    }

    private val night = SleepLogEntry("2026-11-18", 420, true, 480, "2026-11-17T23:00:00.000", "2026-11-18T07:00:00.000")

    @Test fun aValidLoginConnectsWhileGoogleIsStillProcessingSleep() {
        // Logging out and back in during the morning must not fail just because last night is unprocessed.
        assertEquals(listOf(night) to 1_003_000L,
            HealthConnectRules.activation(SleepFetchResult.Pending(listOf(night), 3_000), now = 1_000_000))
        assertEquals(listOf(night) to 0L, HealthConnectRules.activation(SleepFetchResult.Complete(listOf(night)), 1_000_000))
        assertNull(HealthConnectRules.activation(SleepFetchResult.Failed(SyncError.NETWORK), 1_000_000))
    }

    @Test fun aStatusSavedBeforeConnectResultsExistedStillLoads() {
        val old = """{"lastAttemptAt":5,"lastVerifiedAt":4,"phase":"DISCONNECTED","error":null}"""
        val status = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SleepSyncStatus>(old)
        assertEquals(SleepSyncPhase.DISCONNECTED, status.phase)
        assertEquals(0L, status.connectAttemptAt)
        assertEquals("", status.connectResult)
    }

    @Test fun aRefusedSignInKeepsGooglesStatusCode() {
        // A signing key Google doesn't know closes sign-in at once; "closed" alone hid that.
        assertTrue(HealthConnectRules.signInFailure(0, 10).contains("SHA-1"))
        assertEquals("Google sign-in was cancelled (status 16)", HealthConnectRules.signInFailure(0, 16))
        assertTrue(HealthConnectRules.signInFailure(0, null).contains("no status"))
        assertEquals("Google sign-in failed (status 8)", HealthConnectRules.signInFailure(0, 8))
    }
}
