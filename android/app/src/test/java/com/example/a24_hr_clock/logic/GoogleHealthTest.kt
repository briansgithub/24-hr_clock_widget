package com.example.a24_hr_clock.logic

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GoogleHealthTest {
    @Test fun sharedClockFixtures() {
        val text = javaClass.classLoader!!.getResourceAsStream("google-health-sleep.json")!!.bufferedReader().use { it.readText() }
        for (entry in Json.parseToJsonElement(text).jsonArray) {
            val case = entry.jsonObject
            val logs = (GoogleHealthParser.sleep(case.getValue("points").jsonArray.map { it.jsonObject }) as SleepFetchResult.Complete).logs
            assertEquals(case.getValue("dates").jsonArray.map { it.jsonPrimitive.content }, logs.map { it.dateOfSleep })
            assertEquals(case.getValue("timeInBed").jsonArray.map { it.jsonPrimitive.int }, logs.map { it.timeInBed })
            assertEquals(case.getValue("main").jsonArray.map { it.jsonPrimitive.boolean }, logs.map { it.isMainSleep })
        }
    }
    @Test fun malformedErrorDoesNotMasqueradeAsConsentLoss() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"))
            val client = GoogleHealthClient(credentials, base = server.url("/v4").toString())
            assertEquals(SleepFetchResult.Failed(SyncError.SERVER), client.sleep("2026-09-01", "2026-09-15"))
        }
    }

    private fun point(name: String = "main", metadata: String = "\"mainSleep\":true", processed: Boolean = true): JsonObject = Json.parseToJsonElement("""
        {"dataPointName":"users/test/dataTypes/sleep/dataPoints/$name","sleep":{
        "interval":{"startTime":"2026-09-15T02:00:00Z","endTime":"2026-09-15T10:00:00Z","startUtcOffset":"-14400s","endUtcOffset":"-14400s"},
        "type":"STAGES","metadata":{"processed":$processed,$metadata},"summary":{"minutesAsleep":"420"}}}
    """).jsonObject
    private val credentials = object : CredentialProvider { override suspend fun accessToken(rejectedToken: String?) = "test" }

    @Test fun offsetsAndStableIdentity() {
        val log = (GoogleHealthParser.sleep(listOf(point())) as SleepFetchResult.Complete).logs.single()
        assertEquals("2026-09-14T22:00:00.000", log.startTime)
        assertEquals("2026-09-15", log.dateOfSleep)
        assertEquals(480, log.timeInBed)
        assertEquals("google_health", log.provider)
        assertTrue(log.isMainSleep)
    }
    @Test fun explicitMainPrecedesOldExample() {
        val log = (GoogleHealthParser.sleep(listOf(point(metadata = "\"mainSleep\":false,\"main\":true"))) as SleepFetchResult.Complete).logs.single()
        assertFalse(log.isMainSleep)
    }
    @Test fun pendingCanOmitSummary() {
        val p = point(processed = false).toMutableMap()
        p["sleep"] = JsonObject(p.getValue("sleep").jsonObject.filterKeys { it != "summary" })
        val result = GoogleHealthParser.sleep(listOf(JsonObject(p))) as SleepFetchResult.Pending
        assertTrue(result.partialLogs.isEmpty())
    }
    @Test fun allPagesBeforeComplete() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(buildJsonObject {
                put("dataPoints", JsonArray((0..24).map { point(it.toString()) })); put("nextPageToken", "next")
            }.toString()))
            server.enqueue(MockResponse().setBody(buildJsonObject { put("dataPoints", JsonArray(listOf(point("26")))) }.toString()))
            val client = GoogleHealthClient(credentials, base = server.url("/v4").toString())
            val result = client.sleep("2026-09-01", "2026-09-15") as SleepFetchResult.Complete
            assertEquals(26, result.logs.size)
            assertEquals("users/me/dataSourceFamilies/google-sources", server.takeRequest().requestUrl!!.queryParameter("dataSourceFamily"))
            assertEquals("next", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
        }
    }
    @Test fun failedSecondPageDoesNotDelete() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"nextPageToken\":\"next\"}"))
            server.enqueue(MockResponse().setResponseCode(503))
            val client = GoogleHealthClient(credentials, base = server.url("/v4").toString())
            assertEquals(SleepFetchResult.Failed(SyncError.SERVER), client.sleep("2026-09-01", "2026-09-15"))
        }
    }
    @Test fun emptyProtobufIsComplete() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = GoogleHealthClient(credentials, base = server.url("/v4").toString())
            assertEquals(SleepFetchResult.Complete(emptyList<SleepLogEntry>()), client.sleep("2026-09-01", "2026-09-15"))
        }
    }
    @Test fun rateDeadlineAndSingleUnauthorizedRetry() = runTest {
        MockWebServer().use { server ->
            var renewals = 0
            var deadline = 0L
            val auth = object : CredentialProvider { override suspend fun accessToken(rejectedToken: String?): String {
                if (rejectedToken != null) renewals++
                return "token$renewals"
            } }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "300"))
            val client = GoogleHealthClient(auth, defer = { deadline = it }, deadline = { deadline }, base = server.url("/v4").toString(), now = { 100L })
            assertEquals(SleepFetchResult.Failed(SyncError.RATE_LIMIT, 300100), client.sleep("2026-09-01", "2026-09-15"))
            assertEquals(SleepFetchResult.Failed(SyncError.RATE_LIMIT, 300100), client.sleep("2026-09-01", "2026-09-15"))
            assertEquals(1, renewals)
            assertEquals(2, server.requestCount)
        }
    }
}
