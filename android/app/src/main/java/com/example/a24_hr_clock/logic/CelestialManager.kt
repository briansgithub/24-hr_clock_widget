package com.example.a24_hr_clock.logic

import org.shredzone.commons.suncalc.MoonIllumination
import org.shredzone.commons.suncalc.MoonPosition
import org.shredzone.commons.suncalc.SunPosition
import org.shredzone.commons.suncalc.SunTimes
import java.time.ZonedDateTime
import java.util.*
import kotlin.math.*

class CelestialManager(private val latitude: Double, private val longitude: Double) {

    private var sunMaxElev = 0.0
    private var sunMinElev = 0.0
    private var moonMaxElev = 0.0
    private var moonMinElev = 0.0
    private var solarPeakToday = 0.0
    private var lastExtremesUpdate: Long = 0

    fun getSunTimes(): Pair<Double, Double> {
        val times = SunTimes.compute()
            .at(latitude, longitude)
            .today()
            .execute()

        val sunrise = times.rise?.let { zdtToHour(it) } ?: 6.0
        val sunset = times.set?.let { zdtToHour(it) } ?: 18.0
        return Pair(sunrise, sunset)
    }

    private fun updateExtremes() {
        val now = ZonedDateTime.now()
        if (System.currentTimeMillis() - lastExtremesUpdate < 300000) return // 5 min cache

        val sunElevs = mutableListOf<Double>()
        val moonElevs = mutableListOf<Double>()

        for (h in -12..12) {
            val t = now.plusHours(h.toLong())
            sunElevs.add(SunPosition.compute().at(latitude, longitude).on(t).execute().altitude)
            moonElevs.add(MoonPosition.compute().at(latitude, longitude).on(t).execute().altitude)
        }

        sunMaxElev = sunElevs.maxOrNull() ?: 0.0
        sunMinElev = sunElevs.minOrNull() ?: 0.0
        moonMaxElev = moonElevs.maxOrNull() ?: 0.0
        moonMinElev = moonElevs.minOrNull() ?: 0.0
        solarPeakToday = sunMaxElev
        lastExtremesUpdate = System.currentTimeMillis()
    }

    fun getSolarIrradiance(): Int {
        updateExtremes()
        val now = ZonedDateTime.now()
        val sunPos = SunPosition.compute()
            .at(latitude, longitude)
            .on(now)
            .execute()
        
        val elev = sunPos.altitude
        if (elev <= 0 || solarPeakToday <= 0) return 0
        
        // 1. Physics: Irradiance ∝ sin(elevation) * exp(-k / sin(elevation))
        // k = 0.12 is a typical clear-sky extinction coefficient
        val k = 0.12
        fun getIrradiance(e: Double): Double {
            val s = sin(Math.toRadians(max(0.01, e)))
            return s * exp(-k / s)
        }
        
        val raw = getIrradiance(elev)
        val peakRaw = getIrradiance(solarPeakToday)
        
        // 2. Normalise 0.0 - 1.0
        val ratio = raw / peakRaw
        
        // 3. Perception: Gamma Correction (sRGB standard is ~2.2)
        val gamma = 2.2
        val correctedRatio = max(0.0, ratio).pow(1.0 / gamma)
        
        val brightness = (255 * correctedRatio).roundToInt()
        return brightness.coerceIn(0, 255)
    }

    /**
     * Returns (sunRad, moonRad, moonPhaseValue, sunElevation)
     * Radian angles are mapped based on the altitude-based mapping in the Python script.
     */
    fun getCelestialPositions(): SunMoonPosition {
        val now = ZonedDateTime.now()
        updateExtremes()
        
        val sunPos = SunPosition.compute()
            .at(latitude, longitude)
            .on(now)
            .execute()
            
        val moonPos = MoonPosition.compute()
            .at(latitude, longitude)
            .on(now)
            .execute()
            
        val moonIllum = MoonIllumination.compute()
            .on(now)
            .execute()

        val sunRad = calculateMappedAngle(sunPos.altitude, isRising(now, "sun"), sunMaxElev, sunMinElev)
        val moonRad = calculateMappedAngle(moonPos.altitude, isRising(now, "moon"), moonMaxElev, moonMinElev)
        
        val phaseDegrees = moonIllum.phase 
        val moonPhaseValue = ((phaseDegrees + 180.0) % 360.0) / 360.0 * 29.530588
        
        return SunMoonPosition(sunRad, moonRad, moonPhaseValue, sunPos.altitude)
    }

    data class SunMoonPosition(
        val sunRad: Double,
        val moonRad: Double,
        val moonPhase: Double,
        val sunElevation: Double
    )

    private fun calculateMappedAngle(altitude: Double, rising: Boolean, eMax: Double, eMin: Double): Double {
        val valIn = if (altitude >= 0) {
            (altitude / (if (eMax > 0) eMax else 1.0)).coerceIn(-1.0, 1.0)
        } else {
            (altitude / abs(if (eMin < 0) eMin else -1.0)).coerceIn(-1.0, 1.0)
        }
        
        var angle = Math.toDegrees(asin(valIn))
        if (rising) {
            angle = 180.0 - angle
        }
        // Orientation: 18h (6pm) is 0 degrees in our mapping.
        // We negate the angle to match the counter-clockwise rotation of the 24h clock logic.
        return Math.toRadians((-angle) % 360.0)
    }

    private fun isRising(now: ZonedDateTime, body: String): Boolean {
        // Simple approximation: check if altitude is increasing
        val next = now.plusSeconds(1)
        return if (body == "sun") {
            val p1 = SunPosition.compute().at(latitude, longitude).on(now).execute()
            val p2 = SunPosition.compute().at(latitude, longitude).on(next).execute()
            p2.altitude > p1.altitude
        } else {
            val p1 = MoonPosition.compute().at(latitude, longitude).on(now).execute()
            val p2 = MoonPosition.compute().at(latitude, longitude).on(next).execute()
            p2.altitude > p1.altitude
        }
    }

    private fun zdtToHour(zdt: ZonedDateTime): Double {
        return zdt.hour + zdt.minute / 60.0 + zdt.second / 3600.0
    }
}
