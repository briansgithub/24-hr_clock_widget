package com.example.a24_hr_clock.logic

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class FitbitSyncTest {
    private class Store(var value: FitbitCredentials) : FitbitCredentialStore {
        var episodes = 0; var deadline = 0L
        override suspend fun credentials() = value
        override suspend fun saveCredentials(credentials: FitbitCredentials) { value = credentials }
        override suspend fun requireReconnect() { if (value.reconnectEpisode == null) { episodes++; value = value.copy(reconnectEpisode = "episode") } }
        override suspend fun retryAt() = deadline
        override suspend fun deferUntil(time: Long) { deadline = time }
    }
    private fun tokens() = MockResponse().setBody("""{"access_token":"new","refresh_token":"rotated","expires_in":28800,"user_id":"person"}""")
    private fun client(store: Store, server: MockWebServer) = FitbitHttpClient(store, "client", "secret", baseUrl = server.url("/").toString().trimEnd('/'), now = { 1_000_000 })

    @Test fun expiredAndUnknownTokensRenewOnceAcrossOverlappingRequests() = runBlocking {
        for (expiry in listOf(0L, 10L)) MockWebServer().use { server ->
            val store = Store(FitbitCredentials("old", "refresh", expiry))
            val http = client(store, server)
            server.enqueue(tokens().setBodyDelay(50, TimeUnit.MILLISECONDS))
            repeat(8) { server.enqueue(MockResponse().setBody("{}")) }
            (1..8).map { async { http.request("/sleep") } }.awaitAll()
            assertEquals(9, server.requestCount)
            assertEquals("/oauth2/token", server.takeRequest().path)
            repeat(8) { assertEquals("Bearer new", server.takeRequest().getHeader("Authorization")) }
            assertEquals("rotated", store.value.refreshToken)
            assertEquals("person", store.value.accountId)
            assertEquals(0, store.episodes)
        }
    }

    @Test fun unauthorizedRequestRenewsAndRetriesExactlyOnce() = runBlocking {
        MockWebServer().use { server ->
            val store = Store(FitbitCredentials("old", "refresh", 9_000_000))
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(tokens()); server.enqueue(MockResponse().setBody("{}"))
            client(store, server).request("/sleep")
            assertEquals(3, server.requestCount); assertEquals(0, store.episodes)
        }
    }

    @Test fun revokedRefreshCreatesOneEpisodeWithoutRepeatedRefreshes() = runBlocking {
        MockWebServer().use { server ->
            val store = Store(FitbitCredentials("old", "refresh")); val http = client(store, server)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":[{"errorType":"invalid_grant"}]}"""))
            repeat(3) { try { http.accessToken(); fail() } catch (e: FitbitRequestException) { assertEquals(SyncError.AUTH_REQUIRED, e.category) } }
            assertEquals(1, store.episodes); assertEquals(1, server.requestCount)
        }
    }

    @Test fun transientAndConfigurationFailuresNeverRequestConsent() = runBlocking {
        for ((code, body, expected) in listOf(Triple(429, "{}", SyncError.RATE_LIMIT), Triple(503, "{}", SyncError.SERVER),
            Triple(401, """{"error":"invalid_client"}""", SyncError.CONFIGURATION))) MockWebServer().use { server ->
            val store = Store(FitbitCredentials("old", "refresh"))
            server.enqueue(MockResponse().setResponseCode(code).setHeader("fitbit-rate-limit-reset", "120").setBody(body))
            try { client(store, server).accessToken(); fail() } catch (e: FitbitRequestException) { assertEquals(expected, e.category) }
            assertEquals(0, store.episodes)
            if (code == 429) assertEquals(1_120_000, store.deadline)
        }
    }

    @Test fun networkFailureAndCancellationPreserveCredentials() = runBlocking {
        val server = MockWebServer(); server.start(); val store = Store(FitbitCredentials("old", "refresh")); val http = client(store, server); server.close()
        try { http.accessToken(); fail() } catch (e: FitbitRequestException) { assertEquals(SyncError.NETWORK, e.category) }
        assertEquals("refresh", store.value.refreshToken); assertEquals(0, store.episodes)
        MockWebServer().use { waiting ->
            waiting.enqueue(tokens().setBodyDelay(1, TimeUnit.SECONDS))
            val job = launch { client(store, waiting).accessToken() }
            yield(); job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(0, store.episodes)
        }
    }

    private fun log(id: String?, main: Boolean = true, day: String = "2026-09-15", start: String = "00:30", end: String = "08:00") =
        SleepLogEntry(day, 400, main, 450, "${day}T$start:00.000", "${day}T$end:00.000", sourceRecordId = id)

    @Test fun pendingMayOmitSleepAndUsesMilliseconds() {
        val result = SleepResponseParser.parse("""{"meta":{"state":"pending","retryDuration":3500}}""") as SleepFetchResult.Pending
        assertEquals(3500, result.retryAfter); assertTrue(result.partialLogs.isEmpty())
        val partial = SleepResponseParser.parse("""{"meta":{"state":"pending","retryDuration":3000},"sleep":[{"logId":9223372036854775809,"dateOfSleep":"2026-09-15","minutesAsleep":60,"timeInBed":65,"isMainSleep":false,"startTime":"2026-09-15T12:00:00","endTime":"2026-09-15T13:05:00"}]}""") as SleepFetchResult.Pending
        assertEquals("9223372036854775809", partial.partialLogs.single().sourceRecordId)
        assertTrue(SleepResponseParser.parse("{}") is SleepFetchResult.Failed)
        assertEquals(SleepFetchResult.Complete(emptyList()), SleepResponseParser.parse("""{"sleep":[]}"""))
    }

    @Test fun napsCorrectionsDeletionsAndMidnightCrossingsReconcile() {
        val main = log("main").copy(startTime = "2026-09-14T23:30:00.000")
        val nap1 = log("nap1", false, start = "12:00", end = "13:00")
        val nap2 = log("nap2", false, start = "17:00", end = "18:00")
        val first = SleepCache.merge(listOf(main), listOf(nap1), "2026-09-15", "2026-09-15", false)
        val second = SleepCache.merge(first, listOf(nap2), "2026-09-15", "2026-09-15", false)
        assertEquals(3, second.size)
        val correction = nap1.copy(endTime = "2026-09-15T13:15:00.000")
        val complete = SleepCache.merge(second, listOf(main, correction), "2026-09-15", "2026-09-15", true)
        assertEquals(setOf(main, correction), complete.toSet())
        assertEquals(complete, SleepCache.merge(complete, listOf(correction, main), "2026-09-15", "2026-09-15", true))
        assertTrue(SleepCache.merge(complete, emptyList(), "2026-09-15", "2026-09-15", true).isEmpty())
        // Stable provider IDs replace corrected civil dates, including a timezone correction.
        val shifted = main.copy(dateOfSleep = "2026-09-16", startTime = "2026-09-16T03:30:00.000")
        assertEquals(listOf(shifted), SleepCache.merge(listOf(main), listOf(shifted), "2026-09-16", "2026-09-16", true))
    }

    @Test fun legacyCacheDecodesWithoutRecordId() {
        val old = """{"dateOfSleep":"2026-09-15","minutesAsleep":60,"timeInBed":65,"isMainSleep":false,"startTime":"2026-09-15T12:00:00","endTime":"2026-09-15T13:05:00"}"""
        assertNull(Json.decodeFromString<SleepLogEntry>(old).sourceRecordId)
    }

    @Test fun opacityUsesVerificationAtExactThresholds() {
        val fresh = SleepSyncStatus(lastVerifiedAt = 1000)
        assertEquals(1f, fresh.opacity(1000 + 3_600_000 - 1))
        assertEquals(.6f, fresh.opacity(1000 + 3_600_000))
        assertEquals(.6f, fresh.opacity(1000 + 14_400_000 - 1))
        assertEquals(.3f, fresh.opacity(1000 + 14_400_000))
        assertEquals(.3f, fresh.opacity(1001, true)); assertEquals(.3f, SleepSyncStatus().opacity(1001))
        assertEquals(1f, fresh.copy(lastVerifiedAt = 20_000_000).opacity(20_000_001))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun requestDuringFetchIsRetainedAndOverlappingPushesAreCombined() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0; val ranges = mutableListOf<Boolean>()
        val queue = SleepRequestQueue(backgroundScope) { full, _ -> calls++; ranges.add(full); if (calls == 1) release.await(); SleepFetchResult.Complete(emptyList()) }
        val first = queue.request(false, false); runCurrent()
        val next = queue.request(true, true); val third = queue.request(false, true)
        release.complete(Unit); runCurrent()
        first.await(); next.await(); third.await(); assertEquals(listOf(false, true), ranges)
    }

    @Test fun callbackRequiresExactUriStateAndOneUnexpiredCode() {
        fun code(value: String, now: Long = 1001) = OAuthCallback.code(value, "nonce", 1000, now)
        assertEquals("ok", code("fitbit24h://callback?state=nonce&code=ok"))
        assertEquals("ok", code("fitbit24h://callback?state=nonce&code=ok#_=_"))
        for (uri in listOf("fitbit24h://callback.evil?state=nonce&code=ok", "fitbit24h://user@callback?state=nonce&code=ok",
            "fitbit24h://callback/path?state=nonce&code=ok", "fitbit24h://callback?state=wrong&code=ok", "fitbit24h://callback?state=nonce&code=ok&code=bad",
            "fitbit24h://callback?state=nonce&error=access_denied", "fitbit24h://callback?state=nonce&code=ok#code=bad",
            "fitbit24h://callback#state=nonce&code=ok", "fitbit24h://callback?state=wrong&code=ok#_=_")) assertNull(code(uri))
        assertNull(code("fitbit24h://callback?state=nonce&code=ok", 601001))
    }
}
