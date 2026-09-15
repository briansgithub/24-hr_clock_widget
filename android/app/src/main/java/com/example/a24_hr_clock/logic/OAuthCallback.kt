package com.example.a24_hr_clock.logic

import java.net.URI
import java.net.URLDecoder

object OAuthCallback {
    fun code(callback: String, expectedState: String, createdAt: Long, now: Long): String? = try {
        val uri = URI(callback)
        val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })
        // Fitbit's browser redirect can append this inert fragment. Authentication
        // values must still come exclusively from the state-checked query string.
        val validFragment = uri.rawFragment == null || uri.rawFragment == "_=_"
        if (uri.scheme != "fitbit24h" || uri.rawAuthority != "callback" || !uri.path.isNullOrEmpty() || !validFragment ||
            now - createdAt !in 0..600_000 || parameters["state"] != listOf(expectedState) || "error" in parameters) null
        else parameters["code"]?.singleOrNull()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}
