package com.example.a24_hr_clock.wallpaper

import android.content.res.Resources
import android.graphics.*
import android.text.TextPaint
import com.example.a24_hr_clock.R
import com.example.a24_hr_clock.logic.EnergyCalculator
import com.example.a24_hr_clock.logic.TimeZoneUtils
import java.util.*
import kotlin.math.*

class ClockRenderer {

    private var timezoneMapBitmap: Bitmap? = null

    /** Last countdown bounds from a full [draw] pass; used by the wallpaper 1 Hz dirty path. */
    var lastCountdownDrawResult: CountdownDrawResult? = null
        private set

    private val mapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        alpha = 160
    }

    private val meridianPaint = Paint().apply {
        color = Color.parseColor("#FF9F1C")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val locationDotPaint = Paint().apply {
        color = Color.argb(255, 255, 255, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 255
    }

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun ensureMapBitmap(resources: Resources) {
        if (timezoneMapBitmap != null) return
        timezoneMapBitmap = BitmapFactory.decodeResource(resources, R.drawable.world_map_equirectangular)
    }

    private val faceOutlinePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val tickPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = TextPaint().apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val nightShadingPaint = Paint().apply {
        color = Color.parseColor("#2C3E50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val sleepArcPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val grogginessArcPaint = Paint().apply {
        color = Color.parseColor("#60BBBBBB") // Solid light gray with transparency
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val windDownArcPaint = Paint().apply {
        color = Color.parseColor("#60BBBBBB")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        color = Color.parseColor("#FF9F1C")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val sunPaint = Paint().apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val sunOutlinePaint = Paint().apply {
        color = Color.parseColor("#FFA500")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val moonLitPaint = Paint().apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val moonShadowPaint = Paint().apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val eventColors = listOf(
        "#FF5733", "#33FF57", "#3357FF", "#F333FF", "#FF33A1", "#33FFF5",
        "#F5FF33", "#FF8633", "#8633FF", "#33FF86", "#FF3333", "#3333FF",
        "#33FF33", "#FFFF33", "#FF33FF", "#33FFFF", "#FF9933", "#99FF33",
        "#3399FF", "#FF3399", "#9933FF", "#33FF99", "#FF6633", "#66FF33"
    )

    private val eventOutlinePaint = Paint().apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val eventPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val energyCurvePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val solarCirclePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val lifeCalendarRenderer = com.example.a24_hr_clock.logic.LifeCalendarRenderer()

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        now: Calendar,
        sunriseHour: Double,
        sunsetHour: Double,
        sleepLogs: List<com.example.a24_hr_clock.logic.SleepLogEntry> = emptyList(),
        sunRad: Double,
        moonRad: Double,
        moonPhaseValue: Double,
        sunElevation: Double,
        solarIrradiance: Int,
        sleepDebt: Double,
        bathyphaseHour: Double?,
        calendarEvents: List<com.example.a24_hr_clock.logic.CalendarEvent> = emptyList(),
        showNumbers: Boolean = true,
        showSleep: Boolean = true,
        showSunMoon: Boolean = true,
        showSleepDebtText: Boolean = true,
        showEnergy: Boolean = false,
        showCalendar: Boolean = true,
        smallTopRight: Boolean = false,
        showLifeCalendar: Boolean = false,
        showTotalBedtime: Boolean = true,
        showEnergyPct: Boolean = false,
        normalizeEnergy: Boolean = true,
        includeNaps: Boolean = true,
        tauWake: Double = 18.2,
        tauSleep: Double = 4.2,
        tauInertia: Double = 1.5,
        debtFactor: Double = 1.0,
        circadianOffset: Double = 12.0,
        useBathyphase: Boolean = true,
        bedtimeGoal: Double = 9.75,
        showManualWake: Boolean = false,
        manualWakeTime: String = "09:00",
        showWakeSunriseInfo: Boolean = true,
        showTimezoneMap: Boolean = false,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
        showBathyphase: Boolean = false,
        showAcrophase: Boolean = false,
        showGrogginess: Boolean = false,
        showWindDown: Boolean = false,
        showBedtimeCountdown: Boolean = false,
        isPreview: Boolean = false,
        previewIsLockScreen: Boolean = false
    ) {
        // Clear background to OLED black
        canvas.drawColor(Color.BLACK)
        lastCountdownDrawResult = null

        if (showLifeCalendar) {
            lifeCalendarRenderer.draw(canvas, width, height)
        }

        val centerX: Float
        val centerY: Float
        val radius: Float

        if (smallTopRight) {
            // Small clock in top right (~10% smaller than original /6 sizing)
            radius = min(width, height) / 6f * 0.9f
            centerX = width - radius - 80f
            centerY = radius + 150f // Leave some space for status bar
        } else {
            // Large centered clock
            centerX = width / 2f
            radius = min(width, height) / 2f - 150f
            val moonDiameter = max(12f, radius / 7f)
            centerY = height / 2f - (1.75f * moonDiameter)
        }

        if (radius < 20) return

        // 1. Draw solid background
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // Identify Active Sleep Date Logs (Today or Fallback)
        val todayStr = java.time.LocalDate.now().toString()
        var activeLogs = sleepLogs.filter { it.dateOfSleep == todayStr }
        if (activeLogs.isEmpty()) {
            val lastLog = sleepLogs.maxByOrNull { it.dateOfSleep }
            if (lastLog != null) {
                activeLogs = sleepLogs.filter { it.dateOfSleep == lastLog.dateOfSleep }
            }
        }

        // 2. Draw Night Shading
        drawNightShading(canvas, centerX, centerY, radius, sunriseHour, sunsetHour)

        // 3. Draw Sleep Arc
        if (showSleep && activeLogs.isNotEmpty()) {
            drawSleepArcs(canvas, centerX, centerY, radius, activeLogs, includeNaps, showTotalBedtime)
        }

        // 3.5 Draw Wind-down Arc (90 min before asleep)
        if (showWindDown && activeLogs.isNotEmpty()) {
            drawWindDownArc(canvas, centerX, centerY, radius, activeLogs, showTotalBedtime)
        }

        // 3.6 Draw Grogginess Arc
        if (showGrogginess && activeLogs.isNotEmpty()) {
            drawGrogginessArc(canvas, centerX, centerY, radius, activeLogs)
        }

        // 4. Draw Calendar Events
        if (showCalendar) {
            drawCalendarEvents(canvas, centerX, centerY, radius, calendarEvents)
        }

        // 5. Draw Energy Curve
        val mainSleep = activeLogs.find { it.isMainSleep } ?: activeLogs.maxByOrNull { it.endTime }
        
        var wakeHour: Double? = mainSleep?.let {
            try {
                val endDt = java.time.LocalDateTime.parse(it.endTime.replace("Z", ""))
                endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0
            } catch (e: Exception) { null }
        }

        var manualWakeHour: Double? = null
        if (showManualWake) {
            val parts = manualWakeTime.split(":")
            if (parts.size == 2) {
                val h = parts[0].toDoubleOrNull()
                val m = parts[1].toDoubleOrNull()
                if (h != null && m != null) {
                    manualWakeHour = h + m / 60.0
                }
            }
        }

        if (showEnergy && wakeHour != null) {
            val totalAsleep = if (includeNaps) activeLogs.sumOf { it.minutesAsleep / 60.0 } else (mainSleep?.minutesAsleep ?: 0) / 60.0
            drawEnergyCurve(
                canvas, centerX, centerY, radius, wakeHour, sleepDebt, totalAsleep, 
                bathyphaseHour, tauWake, tauSleep, tauInertia, debtFactor, circadianOffset, useBathyphase, normalizeEnergy, bedtimeGoal
            )
        }

        // 6. Draw face outline
        canvas.drawCircle(centerX, centerY, radius, faceOutlinePaint)

        // 6.5 Draw Wake Indicator
        if (showManualWake && manualWakeHour != null) {
            drawWakeIndicator(canvas, centerX, centerY, radius, manualWakeHour)
        }

        // 7. Draw Solar Circle
        drawSolarCircle(canvas, centerX, centerY, radius, solarIrradiance)

        // 8. Draw Sleep Debt Text
        if (showSleepDebtText) {
            drawSleepDebtText(canvas, centerX, centerY, radius, sleepDebt)
        }

        // 9. Draw Ticks and Numbers
        for (h in 0 until 24 step 2) {
            val angleDegrees = (h - 18) * 15.0
            val rad = Math.toRadians(angleDegrees)

            val isMajor = h % 6 == 0
            val tickLen = if (isMajor) radius * 0.15f else radius * 0.08f
            tickPaint.strokeWidth = if (isMajor) 8f else 4f

            val x1 = centerX + (radius - tickLen) * cos(rad).toFloat()
            val y1 = centerY + (radius - tickLen) * sin(rad).toFloat()
            val x2 = centerX + radius * cos(rad).toFloat()
            val y2 = centerY + radius * sin(rad).toFloat()

            // Determine if the tick is in the nighttime region
            val isNightTick = if (sunsetHour > sunriseHour) {
                h >= sunsetHour || h < sunriseHour
            } else {
                h >= sunsetHour && h < sunriseHour
            }

            tickPaint.color = if (isNightTick) Color.WHITE else Color.BLACK
            canvas.drawLine(x1, y1, x2, y2, tickPaint)

            if (showNumbers) {
                val textRadius = radius - tickLen - 40f
                val tx = centerX + textRadius * cos(rad).toFloat()
                val ty = centerY + textRadius * sin(rad).toFloat()
                val displayNum = if (h % 12 == 0) 12 else h % 12
                textPaint.textSize = radius * 0.1f
                textPaint.color = if (isNightTick) Color.WHITE else Color.BLACK
                canvas.drawText(displayNum.toString(), tx, ty + (textPaint.textSize / 3f), textPaint)
            }
        }

        // 9.5 Draw Bathyphase Indicator
        if (showBathyphase && bathyphaseHour != null) {
            var bathyEnergy: Double? = null
            if (wakeHour != null) {
                val totalAsleep = if (includeNaps) activeLogs.sumOf { it.minutesAsleep / 60.0 } else (mainSleep?.minutesAsleep ?: 0) / 60.0
                bathyEnergy = EnergyCalculator.getEnergyLevel(
                    bathyphaseHour, wakeHour, sleepDebt, totalAsleep,
                    if (useBathyphase) bathyphaseHour else null,
                    false, tauWake, tauSleep, tauInertia, debtFactor,
                    if (useBathyphase) null else circadianOffset
                )
            }
            drawBathyphaseIndicator(canvas, centerX, centerY, radius, bathyphaseHour, bathyEnergy)
        }

        // 9.6 Draw Acrophase Indicator & Calculate Peak for %
        var peakEnergyToday = -1.0
        var peakHourToday = 0.0
        var maxEPerfection = -1.0

        if (wakeHour != null) {
            val totalAsleep = if (includeNaps) activeLogs.sumOf { it.minutesAsleep / 60.0 } else (mainSleep?.minutesAsleep ?: 0) / 60.0

            for (i in 0..144) {
                val h = (i.toDouble() / 144) * 24.0

                // Today's curve (with debt and actual sleep)
                val eToday = EnergyCalculator.getEnergyLevel(
                    h, wakeHour, sleepDebt, totalAsleep,
                    if (useBathyphase) bathyphaseHour else null,
                    false, tauWake, tauSleep, tauInertia, debtFactor,
                    if (useBathyphase) null else circadianOffset
                )
                if (eToday > peakEnergyToday) {
                    peakEnergyToday = eToday
                    peakHourToday = h
                }

                // Perfection curve (0 debt, goal sleep)
                val ePerf = EnergyCalculator.getEnergyLevel(
                    h, wakeHour, 0.0, bedtimeGoal,
                    if (useBathyphase) bathyphaseHour else null,
                    false, tauWake, tauSleep, tauInertia, debtFactor,
                    if (useBathyphase) null else circadianOffset
                )
                if (ePerf > maxEPerfection) {
                    maxEPerfection = ePerf
                }
            }

            if (showAcrophase) {
                drawAcrophaseIndicator(canvas, centerX, centerY, radius, peakHourToday, peakEnergyToday)
            }
        }

        // 10. Draw Sun and Moon
        if (showSunMoon) {
            drawSunAndMoon(canvas, centerX, centerY, radius, sunRad, moonRad, moonPhaseValue, sunElevation)
        }

        // 11. Draw Clock Hand
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)
        val exactHour = hour + minute / 60.0 + second / 3600.0

        val handAngle = (exactHour - 18.0) * 15.0
        val handRad = Math.toRadians(handAngle)

        val hx = centerX + radius * cos(handRad).toFloat()
        val hy = centerY + radius * sin(handRad).toFloat()

        val arrowLen = radius * 0.12f
        val arrowAngle = Math.toRadians(30.0)

        val x1 = hx - arrowLen * cos(handRad + arrowAngle).toFloat()
        val y1 = hy - arrowLen * sin(handRad + arrowAngle).toFloat()
        val x2 = hx - arrowLen * cos(handRad - arrowAngle).toFloat()
        val y2 = hy - arrowLen * sin(handRad - arrowAngle).toFloat()

        val lineEndX = (x1 + x2) / 2f
        val lineEndY = (y1 + y2) / 2f

        canvas.drawLine(centerX, centerY, lineEndX, lineEndY, handPaint)

        val path = Path().apply {
            moveTo(hx, hy)
            lineTo(x1, y1)
            lineTo(x2, y2)
            close()
        }
        val arrowPaint = Paint(handPaint).apply { style = Paint.Style.FILL }
        canvas.drawPath(path, arrowPaint)

        if (showEnergyPct && wakeHour != null) {
            val todayStr = java.time.LocalDate.now().toString()
            var activeLogs = sleepLogs.filter { it.dateOfSleep == todayStr }
            if (activeLogs.isEmpty()) {
                val lastLog = sleepLogs.maxByOrNull { it.dateOfSleep }
                if (lastLog != null) activeLogs = sleepLogs.filter { it.dateOfSleep == lastLog.dateOfSleep }
            }
            val mainSleep = activeLogs.find { it.isMainSleep } ?: activeLogs.maxByOrNull { it.endTime }
            val totalAsleep = if (includeNaps) activeLogs.sumOf { it.minutesAsleep / 60.0 } else (mainSleep?.minutesAsleep ?: 0) / 60.0

            drawEnergyPct(canvas, centerX, centerY, radius, exactHour, handRad, wakeHour, sleepDebt, totalAsleep, bathyphaseHour, tauWake, tauSleep, tauInertia, debtFactor, circadianOffset, useBathyphase, maxEPerfection)
        }

        // 11.5 Timezone equirectangular map — fixed lock-screen (large centered) anchor
        // so home/lock and smallTopRight on/off share the same map position.
        if (wakeHour != null && showTimezoneMap) {
            drawTimezoneMap(
                canvas, width, height, wakeHour, sunriseHour,
                userLatitude, userLongitude
            )
        }

        // 11.6 Draw Wake-Sunrise Info
        if (wakeHour != null && showWakeSunriseInfo) {
            drawWakeSunriseInfo(canvas, height, wakeHour, sunriseHour, sunsetHour, smallTopRight)
        }

        // 11.7 Draw Bedtime Countdown (home: right-justified left of small clock; lock: top-right)
        if (showBedtimeCountdown) {
            val bedtimeMillis = com.example.a24_hr_clock.logic.BedtimeNotificationManager.resolveBedtimeMillis(sleepLogs)
            if (bedtimeMillis != null) {
                lastCountdownDrawResult = drawBedtimeCountdown(
                    canvas, width, height, bedtimeMillis, smallTopRight,
                    clockCenterX = centerX, clockCenterY = centerY, clockRadius = radius
                )
            }
        }

        // 12. Draw Preview Overlay
        if (isPreview) {
            val text = if (previewIsLockScreen) "Previewing: Lock Screen (Tap to toggle)" else "Previewing: Home Screen (Tap to toggle)"
            textPaint.textSize = 40f
            textPaint.color = Color.WHITE
            val textWidth = textPaint.measureText(text)
            val padding = 20f
            val rect = RectF(
                centerX - textWidth / 2f - padding,
                150f - textPaint.textSize - padding,
                centerX + textWidth / 2f + padding,
                150f + padding
            )
            val bgPaint = Paint().apply {
                color = Color.parseColor("#AA000000")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
            
            // Adjust descent so it's centered
            val textY = 150f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, centerX, textY, textPaint)
        }
    }

    private fun drawTimezoneMap(
        canvas: Canvas,
        width: Int,
        height: Int,
        wakeHour: Double,
        sunriseHour: Double,
        userLatitude: Double?,
        userLongitude: Double?
    ) {
        val bitmap = timezoneMapBitmap ?: return

        // Anchor to the large centered clock layout (lock default), not the active dial.
        val clockRadius = min(width, height) / 2f - 150f
        if (clockRadius < 20f) return
        val moonDiameter = max(12f, clockRadius / 7f)
        val clockCenterY = height / 2f - (1.75f * moonDiameter)

        val sidePad = 40f
        val maxMapHeight = height * 0.28f
        // Clear the sun/moon orbit (radius + icon + padding) so icons sit above the map.
        val iconSize = max(12f, clockRadius / 7f)
        val orbitOuter = clockRadius + iconSize + 20f + iconSize / 1.6f
        val mapTop = clockCenterY + orbitOuter + 16f
        val availBottom = height - 36f
        if (mapTop >= availBottom - 40f) return

        val availHeight = (availBottom - mapTop).coerceAtMost(maxMapHeight).coerceAtLeast(40f)
        val availWidth = (width - sidePad * 2).coerceAtLeast(80f)

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val scale = min(availWidth / bmpW, availHeight / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val left = (width - drawW) / 2f
        val top = mapTop

        val dst = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dst, mapPaint)

        val offsetHours = TimeZoneUtils.correlatedTimezoneOffsetHours(wakeHour, sunriseHour)
        val lon = TimeZoneUtils.longitudeForUtcOffsetHours(offsetHours)
        val meridianX = left + ((lon + 180.0) / 360.0).toFloat() * drawW
        meridianPaint.strokeWidth = max(3f, drawW * 0.004f)
        canvas.drawLine(meridianX, top, meridianX, top + drawH, meridianPaint)

        if (userLatitude != null && userLongitude != null) {
            val (dotX, dotY) = latLonToMapPoint(userLatitude, userLongitude, left, top, drawW, drawH)
            // Midpoint between original (1.5 / 0.25%) and previous bump (3.5 / 0.6%).
            val dotR = max(2.5f, min(drawW, drawH) * 0.00425f)
            locationDotPaint.color = Color.argb(255, 255, 255, 255)
            locationDotPaint.alpha = 255
            canvas.drawCircle(dotX, dotY, dotR, locationDotPaint)
        }
    }

    /** Map lon/lat onto the drawn equirectangular bitmap (lon −180…180, lat −90…90). */
    private fun latLonToMapPoint(
        lat: Double,
        lon: Double,
        left: Float,
        top: Float,
        drawW: Float,
        drawH: Float
    ): Pair<Float, Float> {
        var wrappedLon = lon
        while (wrappedLon > 180.0) wrappedLon -= 360.0
        while (wrappedLon < -180.0) wrappedLon += 360.0
        val clampedLat = lat.coerceIn(-90.0, 90.0)
        val x = left + ((wrappedLon + 180.0) / 360.0).toFloat() * drawW
        val y = top + ((90.0 - clampedLat) / 180.0).toFloat() * drawH
        return x to y
    }

    private fun drawEnergyPct(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        exactHour: Double,
        handRad: Double,
        wakeHour: Double,
        sleepDebt: Double,
        sleepDuration: Double,
        bathyphaseHour: Double?,
        tauWake: Double,
        tauSleep: Double,
        tauInertia: Double,
        debtFactor: Double,
        circadianOffset: Double,
        useBathyphase: Boolean,
        maxEPerfection: Double
    ) {
        val currentE = EnergyCalculator.getEnergyLevel(
            exactHour, wakeHour, sleepDebt, sleepDuration,
            if (useBathyphase) bathyphaseHour else null,
            false, tauWake, tauSleep, tauInertia, debtFactor,
            if (useBathyphase) null else circadianOffset
        )

        val pct = if (maxEPerfection > 0) (currentE / maxEPerfection * 100).roundToInt().coerceAtLeast(0) else 0
        
        val textRadius = radius + 60f
        val tx = cx + textRadius * cos(handRad).toFloat()
        val ty = cy + textRadius * sin(handRad).toFloat()

        textPaint.textSize = radius * 0.08f
        textPaint.color = Color.BLACK
        // Simple outline
        for (dx in -2..2 step 2) {
            for (dy in -2..2 step 2) {
                canvas.drawText("${pct}%", tx + dx, ty + dy + (textPaint.textSize / 3f), textPaint)
            }
        }
        textPaint.color = interpolateEnergyColor(currentE)
        canvas.drawText("${pct}%", tx, ty + (textPaint.textSize / 3f), textPaint)
    }

    private fun drawCalendarEvents(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        events: List<com.example.a24_hr_clock.logic.CalendarEvent>
    ) {
        val margin = radius * 0.15f
        val rArc = radius - margin
        val strokeWidth = max(6f, radius / 9f)
        val rect = RectF(cx - rArc, cy - rArc, cx + rArc, cy + rArc)
        
        eventOutlinePaint.strokeWidth = strokeWidth + 2f
        eventPaint.strokeWidth = strokeWidth

        events.forEachIndexed { i, event ->
            if (event.isAllDay) return@forEachIndexed

            val duration = if (event.endHour >= event.startHour) event.endHour - event.startHour else (event.endHour + 24.0) - event.startHour
            if (duration < 0.05) return@forEachIndexed
            
            val startAngle = (event.startHour - 18.0) * 15.0
            val sweepAngle = duration * 15.0
            
            // Outline
            canvas.drawArc(rect, startAngle.toFloat(), sweepAngle.toFloat(), false, eventOutlinePaint)
            
            // Fill
            val colorStr = if (event.isAllDay) "#8C7EFF" else eventColors[i % eventColors.size]
            eventPaint.color = Color.parseColor(colorStr)
            canvas.drawArc(rect, startAngle.toFloat(), sweepAngle.toFloat(), false, eventPaint)
        }
    }

    private fun drawSolarCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, brightness: Int) {
        val circleR = radius * (2f / 13f)
        solarCirclePaint.color = Color.rgb(brightness, brightness, brightness)
        canvas.drawCircle(cx, cy, circleR, solarCirclePaint)
    }

    private fun drawEnergyCurve(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        wakeHour: Double,
        sleepDebt: Double,
        sleepDuration: Double,
        bathyphaseHour: Double?,
        tauWake: Double,
        tauSleep: Double,
        tauInertia: Double,
        debtFactor: Double,
        circadianOffset: Double,
        useBathyphase: Boolean,
        normalize: Boolean,
        bedtimeGoal: Double
    ) {
        val steps = 72
        var lastX = 0f
        var lastY = 0f

        // Pre-calculate levels for normalization
        val levels = mutableListOf<Double>()
        for (i in 0..steps) {
            val h = (i.toDouble() / steps) * 24.0
            levels.add(EnergyCalculator.getEnergyLevel(
                h, wakeHour, sleepDebt, sleepDuration, 
                if (useBathyphase) bathyphaseHour else null, 
                false, tauWake, tauSleep, tauInertia, debtFactor, 
                if (useBathyphase) null else circadianOffset
            ))
        }
        val eMin = levels.minOrNull() ?: 0.0
        val eMax = levels.maxOrNull() ?: 1.0
        val eRange = eMax - eMin

        for (i in 0..steps) {
            val h = (i.toDouble() / steps) * 24.0
            val energy = levels[i]
            
            // Scaled radius (matching Python get_display_radius logic)
            val displayEnergy = if (normalize && eRange > 0.01) (energy - eMin) / eRange else energy.coerceIn(0.0, 1.0)
            val currentR = (0.10 + 0.80 * displayEnergy) * radius

            val angleDegrees = (h - 18.0) * 15.0
            val rad = Math.toRadians(angleDegrees)
            val px = cx + currentR.toFloat() * cos(rad).toFloat()
            val py = cy + currentR.toFloat() * sin(rad).toFloat()

            if (i > 0) {
                energyCurvePaint.color = interpolateEnergyColor(energy)
                canvas.drawLine(lastX, lastY, px, py, energyCurvePaint)
            }
            lastX = px
            lastY = py
        }
    }

    private fun interpolateEnergyColor(energy: Double): Int {
        val v = energy.coerceIn(0.0, 1.0)
        val r = (0 * (1 - v) + 255 * v).toInt()
        val g = (210 * (1 - v) + 75 * v).toInt()
        val b = (255 * (1 - v) + 43 * v).toInt()
        return Color.rgb(r, g, b)
    }

    /** Result of drawing or laying out the bedtime countdown overlay. */
    data class CountdownDrawResult(
        /** Axis-aligned bounds of the current countdown stack (content, not including prior frame). */
        val contentDirty: Rect,
        val isOverdue: Boolean,
        /** True when [contentDirty] overlaps the clock face — partial black clears are unsafe. */
        val intersectsClock: Boolean
    )

    /**
     * Layout + draw bedtime countdown. Returns dirty bounds for partial (1 Hz) updates.
     * When [clearRect] is non-null, fills that rect with black before drawing (dirty-rect path).
     */
    fun drawBedtimeCountdown(
        canvas: Canvas,
        width: Int,
        height: Int,
        bedtimeMillis: Long,
        smallTopRight: Boolean,
        clockCenterX: Float,
        clockCenterY: Float,
        clockRadius: Float,
        clearRect: Rect? = null
    ): CountdownDrawResult {
        val layout = layoutBedtimeCountdown(
            width, height, bedtimeMillis, smallTopRight,
            clockCenterX, clockCenterY, clockRadius
        )
        if (clearRect != null) {
            canvas.drawRect(clearRect, countdownClearPaint)
        }
        canvas.drawText("Bedtime", layout.x, layout.labelBaseline, layout.labelPaint)
        canvas.drawText(layout.countdownStr, layout.x, layout.countdownBaseline, layout.countdownPaint)
        if (!layout.isOverdue) {
            canvas.drawText(layout.targetStr, layout.x, layout.targetBaseline, layout.targetPaint)
        }
        return CountdownDrawResult(layout.contentDirty, layout.isOverdue, layout.intersectsClock)
    }

    /** Measure countdown bounds without drawing (for dirty-rect lock planning). */
    fun measureBedtimeCountdown(
        width: Int,
        height: Int,
        bedtimeMillis: Long,
        smallTopRight: Boolean,
        clockCenterX: Float,
        clockCenterY: Float,
        clockRadius: Float
    ): CountdownDrawResult {
        val layout = layoutBedtimeCountdown(
            width, height, bedtimeMillis, smallTopRight,
            clockCenterX, clockCenterY, clockRadius
        )
        return CountdownDrawResult(layout.contentDirty, layout.isOverdue, layout.intersectsClock)
    }

    /** Whether a 1 Hz dirty-rect tick is useful (active countdown, not static overdue text). */
    fun isBedtimeCountdownTicking(bedtimeMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        var targetMillis = bedtimeMillis
        if (now >= targetMillis) {
            targetMillis += 24L * 60L * 60L * 1000L
        }
        val lastBedtimeMillis = targetMillis - (24L * 60L * 60L * 1000L)
        return !(now >= lastBedtimeMillis && now < lastBedtimeMillis + (6L * 60L * 60L * 1000L))
    }

    private data class CountdownLayout(
        val x: Float,
        val labelBaseline: Float,
        val countdownBaseline: Float,
        val targetBaseline: Float,
        val countdownStr: String,
        val targetStr: String,
        val isOverdue: Boolean,
        val labelPaint: TextPaint,
        val countdownPaint: TextPaint,
        val targetPaint: TextPaint,
        val contentDirty: Rect,
        val intersectsClock: Boolean
    )

    private val countdownClearPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private fun layoutBedtimeCountdown(
        width: Int,
        height: Int,
        bedtimeMillis: Long,
        smallTopRight: Boolean,
        clockCenterX: Float,
        clockCenterY: Float,
        clockRadius: Float
    ): CountdownLayout {
        val now = System.currentTimeMillis()
        var targetMillis = bedtimeMillis
        // Keep target in the future if it just passed during rendering.
        if (now >= targetMillis) {
            targetMillis += 24L * 60L * 60L * 1000L
        }

        val lastBedtimeMillis = targetMillis - (24L * 60L * 60L * 1000L)
        val isOverdue = now >= lastBedtimeMillis && now < lastBedtimeMillis + (6L * 60L * 60L * 1000L)
        val minutesRemaining = (targetMillis - now) / 60000.0

        val color = when {
            isOverdue -> Color.parseColor("#888888")
            minutesRemaining > 90 -> Color.WHITE
            else -> {
                val progress = ((90 - minutesRemaining) / 90.0).coerceIn(0.0, 1.0).toFloat()
                val channel = (255 - (255 - 136) * progress).toInt()
                Color.rgb(channel, channel, channel)
            }
        }

        val bedtime = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(targetMillis),
            java.time.ZoneId.systemDefault()
        )
        val targetStr = bedtime.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))

        val countdownStr = if (isOverdue) {
            "GO TO BED!"
        } else {
            val totalSeconds = ((targetMillis - now) / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        }

        // Home (small clock top-right): right-justified clear of sun/moon orbit;
        // countdown glyph bottoms flush with the dial's outer circumference.
        // Lock (large centered clock): top-right, opposite the system clock.
        val paddingX = 48f
        val alignRight: Boolean
        val x: Float
        if (smallTopRight) {
            // Match drawSunAndMoon orbit so text stays left of sun/moon at any angle.
            val iconSize = max(12f, clockRadius / 7f)
            val orbitRadius = clockRadius + iconSize + 20f
            val celestialRadius = iconSize / 1.6f
            val leftmostCelestial = clockCenterX - orbitRadius - celestialRadius
            alignRight = true
            x = leftmostCelestial - 20f
        } else {
            alignRight = true
            x = width - paddingX
        }

        val labelPaint = TextPaint().apply {
            this.color = color
            textSize = 36f
            textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            alpha = 200
        }
        val countdownPaint = TextPaint().apply {
            this.color = color
            textSize = 64f
            textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val targetPaint = TextPaint().apply {
            this.color = color
            textSize = 40f
            textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            alpha = 220
        }

        val countdownBaseline: Float
        val labelBaseline: Float
        if (smallTopRight) {
            // Outer edge of the stroked circumference, then bottom-align via this string's bounds.
            val clockBottom = clockCenterY + clockRadius + faceOutlinePaint.strokeWidth / 2f
            val glyphBounds = Rect()
            countdownPaint.getTextBounds(countdownStr, 0, countdownStr.length, glyphBounds)
            countdownBaseline = clockBottom - glyphBounds.bottom
            val labelGap = 8f
            labelBaseline = countdownBaseline + countdownPaint.ascent() - labelGap - labelPaint.descent()
        } else {
            val topY = (height * 0.20f).coerceIn(260f, height * 0.40f)
            labelBaseline = topY
            countdownBaseline = topY + countdownPaint.textSize + 8f
        }
        val targetBaseline = countdownBaseline + targetPaint.textSize + 10f

        // Widen clear region for longest plausible countdown / overdue string so shrinking text
        // does not leave ghosts even before union with the previous frame.
        val measureCountdown = if (isOverdue) countdownStr else maxOf(
            countdownStr,
            "23:59:59",
            "GO TO BED!"
        )
        val measureTarget = if (isOverdue) "" else targetStr
        val labelWidth = labelPaint.measureText("Bedtime")
        val countdownWidth = countdownPaint.measureText(measureCountdown)
        val targetWidth = if (measureTarget.isEmpty()) 0f else targetPaint.measureText(measureTarget)
        val stackWidth = maxOf(labelWidth, countdownWidth, targetWidth)

        val aaPad = 8
        val left: Int
        val right: Int
        if (alignRight) {
            right = (x + aaPad).toInt().coerceAtMost(width)
            left = (x - stackWidth - aaPad).toInt().coerceAtLeast(0)
        } else {
            left = (x - aaPad).toInt().coerceAtLeast(0)
            right = (x + stackWidth + aaPad).toInt().coerceAtMost(width)
        }

        val top = (labelBaseline + labelPaint.ascent() - aaPad).toInt().coerceAtLeast(0)
        val bottomY = if (isOverdue) {
            countdownBaseline + countdownPaint.descent()
        } else {
            targetBaseline + targetPaint.descent()
        }
        val bottom = (bottomY + aaPad).toInt().coerceAtMost(height)
        val contentDirty = Rect(left, top, right, bottom)

        val clockOuter = clockRadius + faceOutlinePaint.strokeWidth / 2f
        val intersectsClock = rectIntersectsCircle(
            contentDirty, clockCenterX, clockCenterY, clockOuter
        )

        return CountdownLayout(
            x = x,
            labelBaseline = labelBaseline,
            countdownBaseline = countdownBaseline,
            targetBaseline = targetBaseline,
            countdownStr = countdownStr,
            targetStr = targetStr,
            isOverdue = isOverdue,
            labelPaint = labelPaint,
            countdownPaint = countdownPaint,
            targetPaint = targetPaint,
            contentDirty = contentDirty,
            intersectsClock = intersectsClock
        )
    }

    private fun rectIntersectsCircle(rect: Rect, cx: Float, cy: Float, radius: Float): Boolean {
        val nearestX = cx.coerceIn(rect.left.toFloat(), rect.right.toFloat())
        val nearestY = cy.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
        val dx = nearestX - cx
        val dy = nearestY - cy
        return dx * dx + dy * dy <= radius * radius
    }

    private fun drawWakeSunriseInfo(
        canvas: Canvas,
        height: Int,
        wakeHour: Double,
        sunriseHour: Double,
        sunsetHour: Double,
        smallTopRight: Boolean
    ) {
        val offset = wakeHour - sunriseHour
        val prefix = if (offset > 0) "-" else "+"
        val offsetVal = String.format(Locale.US, "%s%.1f hrs", prefix, abs(offset))

        val clampedOffset = TimeZoneUtils.correlatedTimezoneOffsetHours(wakeHour, sunriseHour)

        val utcZone = TimeZoneUtils.getUtcTimeZoneStringForOffset(clampedOffset)
        val tzName = TimeZoneUtils.getTimeZoneNameForOffset(clampedOffset)
        val tzVal = "$utcZone, '$tzName'"

        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        val sunriseH = sunriseHour.toInt()
        val sunriseM = ((sunriseHour - sunriseH) * 60).toInt()
        val sunriseTime = java.time.LocalTime.of(sunriseH % 24, sunriseM)
        val sunriseVal = sunriseTime.format(timeFormatter).lowercase()

        val sunsetH = sunsetHour.toInt()
        val sunsetM = ((sunsetHour - sunsetH) * 60).toInt()
        val sunsetTime = java.time.LocalTime.of(sunsetH % 24, sunsetM)
        val sunsetVal = sunsetTime.format(timeFormatter).lowercase()

        val location = TimeZoneUtils.getMostPopulousLocationForOffset(clampedOffset)

        val infoPaint = TextPaint().apply {
            color = Color.WHITE
            textSize = 50f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val padding = 40f
        val lineSpacing = 15f
        val x = padding
        var y = height - padding

        if (smallTopRight) {
            y -= 5 * (infoPaint.textSize + lineSpacing)
        }

        // Vertical alignment calculation
        val labelWidth = infoPaint.measureText("Timezone: ") + 40f

        // Draw from bottom up
        // Line 5: Sunset
        canvas.drawText("Sunset:", x, y, infoPaint)
        canvas.drawText(sunsetVal, x + labelWidth, y, infoPaint)
        y -= (infoPaint.textSize + lineSpacing)

        // Line 4: Sunrise
        canvas.drawText("Sunrise:", x, y, infoPaint)
        canvas.drawText(sunriseVal, x + labelWidth, y, infoPaint)
        y -= (infoPaint.textSize + lineSpacing)

        // Line 3: Location (City)
        canvas.drawText(location, x + labelWidth, y, infoPaint)
        y -= (infoPaint.textSize + lineSpacing)

        // Line 2: Timezone
        canvas.drawText("Timezone:", x, y, infoPaint)
        canvas.drawText(tzVal, x + labelWidth, y, infoPaint)
        y -= (infoPaint.textSize + lineSpacing)

        // Line 1: Offset
        canvas.drawText("Offset:", x, y, infoPaint)
        canvas.drawText(offsetVal, x + labelWidth, y, infoPaint)
    }

    private fun drawSleepDebtText(canvas: Canvas, cx: Float, cy: Float, radius: Float, debt: Double) {
        val textDist = radius * 0.5f
        // Midnight (0h) is at the bottom (90 degrees in Android coordinate system)
        val rad = Math.toRadians(90.0)
        val tx = cx + textDist * cos(rad).toFloat()
        val ty = cy + textDist * sin(rad).toFloat()

        textPaint.textSize = radius * 0.12f
        // Using a light color for better contrast against dark shading/night
        textPaint.color = Color.parseColor("#EEEEEE")
        
        val debtVal = String.format(Locale.US, "%.1fh", debt)
        canvas.drawText(debtVal, tx, ty, textPaint)
    }

    private fun drawBathyphaseIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float, bathyHour: Double, energyValue: Double?) {
        val angleDegrees = (bathyHour - 18) * 15.0

        val trianglePaint = Paint().apply {
            color = if (energyValue != null) interpolateEnergyColor(energyValue) else Color.parseColor("#1E6363")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val smallTickHeight = radius * 0.08f
        val triangleSize = smallTickHeight
        val tipRadius = radius - triangleSize
        val baseRadius = radius
        val baseWidth = triangleSize * 0.8f

        canvas.save()
        canvas.rotate(angleDegrees.toFloat(), cx, cy)

        val path = Path()
        // Tip of the triangle pointing inwards
        path.moveTo(cx + tipRadius, cy)
        // Base points on the perimeter
        path.lineTo(cx + baseRadius, cy - baseWidth / 2f)
        path.lineTo(cx + baseRadius, cy + baseWidth / 2f)
        path.close()

        canvas.drawPath(path, trianglePaint)
        canvas.restore()
    }

    private fun drawAcrophaseIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float, acroHour: Double, energyValue: Double) {
        val angleDegrees = (acroHour - 18) * 15.0

        val trianglePaint = Paint().apply {
            color = interpolateEnergyColor(energyValue)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val smallTickHeight = radius * 0.08f
        val triangleSize = smallTickHeight
        // Pointing inwards: tip is inner, base is at radius
        val tipRadius = radius - triangleSize
        val baseRadius = radius
        val baseWidth = triangleSize * 0.8f

        canvas.save()
        canvas.rotate(angleDegrees.toFloat(), cx, cy)

        val path = Path()
        path.moveTo(cx + tipRadius, cy)
        path.lineTo(cx + baseRadius, cy - baseWidth / 2f)
        path.lineTo(cx + baseRadius, cy + baseWidth / 2f)
        path.close()

        canvas.drawPath(path, trianglePaint)
        canvas.restore()
    }

    private fun drawNightShading(canvas: Canvas, cx: Float, cy: Float, radius: Float, sunrise: Double, sunset: Double) {
        var nightHours = sunrise - sunset
        if (nightHours < 0) nightHours += 24.0
        val sunsetAngle = (sunset - 18.0) * 15.0
        val sweepAngle = nightHours * 15.0
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, sunsetAngle.toFloat(), sweepAngle.toFloat(), true, nightShadingPaint)
    }

    private fun drawSleepArcs(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        targetLogs: List<com.example.a24_hr_clock.logic.SleepLogEntry>,
        includeNaps: Boolean,
        showTotalBedtime: Boolean
    ) {
        targetLogs.forEach { log ->
            if (!includeNaps && !log.isMainSleep) return@forEach
            
            try {
                val startDt = java.time.LocalDateTime.parse(log.startTime.replace("Z", ""))
                val endDt = java.time.LocalDateTime.parse(log.endTime.replace("Z", ""))
                
                var sH = startDt.hour + startDt.minute / 60.0 + startDt.second / 3600.0
                val eH = endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0
                
                if (!showTotalBedtime) {
                    val durHrs = log.minutesAsleep / 60.0
                    sH = (eH - durHrs).mod(24.0)
                }
                
                var displayDur = eH - sH
                if (displayDur < 0) displayDur += 24.0
                
                val sleepStartAngle = (sH - 18.0) * 15.0
                val sleepSweep = displayDur * 15.0
                val margin = radius * 0.15f
                val rect = RectF(cx - (radius - margin), cy - (radius - margin), cx + (radius - margin), cy + (radius - margin))
                
                sleepArcPaint.color = if (log.isMainSleep) Color.parseColor("#6A5ACD") else Color.parseColor("#8A7AED")
                canvas.drawArc(rect, sleepStartAngle.toFloat(), sleepSweep.toFloat(), true, sleepArcPaint)
            } catch (e: Exception) {}
        }
    }

    private fun drawGrogginessArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        targetLogs: List<com.example.a24_hr_clock.logic.SleepLogEntry>
    ) {
        val mainSleep = targetLogs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep } ?: return
        
        try {
            val endDt = java.time.LocalDateTime.parse(mainSleep.endTime.replace("Z", ""))
            val wakeHour = endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0
            
            val originalStartAngle = (wakeHour - 18.0) * 15.0
            val startAngle = originalStartAngle - 0.5
            val sweepAngle = 1.5 * 15.0 + 0.5
            
            val margin = radius * 0.15f
            val rect = RectF(cx - (radius - margin), cy - (radius - margin), cx + (radius - margin), cy + (radius - margin))
            
            // Create a gradient that goes from dark gray to light gray over the duration of the wedge
            val darkGray = Color.parseColor("#FF555555")
            val lightGray = Color.parseColor("#FFEEEEEE")
            
            // Wrap the gradient color array with darkGray at the end (1.0f)
            // This prevents the SweepGradient wrapping filter from blending lightGray into the start (0.0f) position.
            val shader = SweepGradient(
                cx, cy,
                intArrayOf(darkGray, lightGray, darkGray),
                floatArrayOf(0f, (sweepAngle / 360f).toFloat(), 1f)
            )
            
            // Rotate the shader so its 0 position aligns with the original wake-up start angle
            val matrix = Matrix()
            matrix.postRotate(originalStartAngle.toFloat(), cx, cy)
            shader.setLocalMatrix(matrix)
            
            grogginessArcPaint.shader = shader
            grogginessArcPaint.alpha = 255
            canvas.drawArc(rect, startAngle.toFloat(), sweepAngle.toFloat(), true, grogginessArcPaint)
        } catch (e: Exception) {}
    }

    private fun drawWindDownArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        targetLogs: List<com.example.a24_hr_clock.logic.SleepLogEntry>,
        showTotalBedtime: Boolean
    ) {
        val mainSleep = targetLogs.filter { it.isMainSleep }.maxByOrNull { it.dateOfSleep } ?: return

        try {
            val startDt = java.time.LocalDateTime.parse(mainSleep.startTime.replace("Z", ""))
            val endDt = java.time.LocalDateTime.parse(mainSleep.endTime.replace("Z", ""))

            var asleepHour = startDt.hour + startDt.minute / 60.0 + startDt.second / 3600.0
            if (!showTotalBedtime) {
                val eH = endDt.hour + endDt.minute / 60.0 + endDt.second / 3600.0
                asleepHour = (eH - mainSleep.minutesAsleep / 60.0).mod(24.0)
            }

            // 90 minutes before the purple sleep wedge; slight overlap into sleep for seamless blend
            val asleepAngle = (asleepHour - 18.0) * 15.0
            val originalStartAngle = asleepAngle - 1.5 * 15.0
            val startAngle = originalStartAngle
            val sweepAngle = 1.5 * 15.0 + 0.5

            val margin = radius * 0.15f
            val rect = RectF(cx - (radius - margin), cy - (radius - margin), cx + (radius - margin), cy + (radius - margin))

            // Light → dark (#5C5C5C → #111111)
            val startGray = Color.parseColor("#FF5C5C5C")
            val endGray = Color.parseColor("#FF111111")

            val shader = SweepGradient(
                cx, cy,
                intArrayOf(startGray, endGray, startGray),
                floatArrayOf(0f, (sweepAngle / 360f).toFloat(), 1f)
            )

            val matrix = Matrix()
            matrix.postRotate(originalStartAngle.toFloat(), cx, cy)
            shader.setLocalMatrix(matrix)

            windDownArcPaint.shader = shader
            windDownArcPaint.alpha = 255
            canvas.drawArc(rect, startAngle.toFloat(), sweepAngle.toFloat(), true, windDownArcPaint)
        } catch (e: Exception) {}
    }

    private fun drawWakeIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float, wakeHour: Double) {
        val angleDegrees = (wakeHour - 18.0) * 15.0
        val rad = Math.toRadians(angleDegrees)
        
        val tickLen = radius * 0.25f
        val halfLen = tickLen / 2f
        
        // Calculate points
        val x1 = cx + (radius - halfLen) * cos(rad).toFloat()
        val y1 = cy + (radius - halfLen) * sin(rad).toFloat()
        val x2 = cx + (radius + halfLen) * cos(rad).toFloat()
        val y2 = cy + (radius + halfLen) * sin(rad).toFloat()
        
        // Slightly longer points for the black outline
        val outlineOffset = 1.5f
        val bx1 = cx + (radius - halfLen - outlineOffset) * cos(rad).toFloat()
        val by1 = cy + (radius - halfLen - outlineOffset) * sin(rad).toFloat()
        val bx2 = cx + (radius + halfLen + outlineOffset) * cos(rad).toFloat()
        val by2 = cy + (radius + halfLen + outlineOffset) * sin(rad).toFloat()
        
        val paint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.BUTT
        }
        
        // 1. Black outline
        paint.color = Color.BLACK
        paint.strokeWidth = 14f // Roughly equivalent to Python's width=7 for this scale
        canvas.drawLine(bx1, by1, bx2, by2, paint)
        
        // 2. White center
        paint.color = Color.WHITE
        paint.strokeWidth = 8f // Roughly equivalent to Python's width=4 for this scale
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun drawSunAndMoon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        sunRad: Double,
        moonRad: Double,
        moonPhase: Double,
        sunElevation: Double
    ) {
        // Guard: If orbit logic results in default 0 coordinates at center, don't draw
        if (sunRad == 0.0 && moonRad == 0.0) return

        val iconSize = max(12f, radius / 7f)
        val orbitRadius = radius + iconSize + 20f

        val sx = cx + orbitRadius * cos(sunRad).toFloat()
        val sy = cy + orbitRadius * sin(sunRad).toFloat()
        val mx = cx + orbitRadius * cos(moonRad).toFloat()
        val my = cy + orbitRadius * sin(moonRad).toFloat()

        // Sun
        val sunR = iconSize / 1.6f
        
        // Dynamic sun color based on elevation
        val (sunColor, sunAlpha) = interpolateSunColor(sunElevation)
        sunPaint.color = sunColor
        sunPaint.alpha = sunAlpha
        
        // Sun Outline - also dynamic but slightly more orange/darker
        sunOutlinePaint.color = interpolateSunOutlineColor(sunElevation)
        sunOutlinePaint.alpha = sunAlpha

        canvas.drawCircle(sx, sy, sunR, sunPaint)
        canvas.drawCircle(sx, sy, sunR, sunOutlinePaint)

        // Moon
        val p = (moonPhase / 29.530588) % 1.0
        val mR = iconSize / 1.6f
        canvas.drawCircle(mx, my, mR, moonShadowPaint)

        if (p in 0.01..0.99) {
            if (p <= 0.5) {
                val rect = RectF(mx - mR, my - mR, mx + mR, my + mR)
                canvas.drawArc(rect, -90f, 180f, true, moonLitPaint)
                val midP = (p * 4) - 1
                val eWidth = abs(midP).toFloat() * mR
                val ePaint = if (midP < 0) moonShadowPaint else moonLitPaint
                val eRect = RectF(mx - eWidth, my - mR, mx + eWidth, my + mR)
                canvas.drawOval(eRect, ePaint)
            } else {
                val rect = RectF(mx - mR, my - mR, mx + mR, my + mR)
                canvas.drawArc(rect, 90f, 180f, true, moonLitPaint)
                val midP = ((p - 0.5) * 4) - 1
                val eWidth = abs(midP).toFloat() * mR
                val ePaint = if (midP < 0) moonLitPaint else moonShadowPaint
                val eRect = RectF(mx - eWidth, my - mR, mx + eWidth, my + mR)
                canvas.drawOval(eRect, ePaint)
            }
        } else if (p > 0.49 && p < 0.51) {
            canvas.drawCircle(mx, my, mR, moonLitPaint)
        }
    }
    private fun interpolateSunColor(elevation: Double): Pair<Int, Int> {
        val darkestElev = -8.0
        
        if (elevation >= 0) {
            // Day/Golden Hour: Fully opaque
            val t = (elevation / 20.0).coerceIn(0.0, 1.0)
            
            // Transition from Deep Orange (#FF4500) at horizon to Bright Yellow (#FFD700) at 20+ deg
            val r = 255
            val g = (69 * (1 - t) + 215 * t).toInt()
            val b = 0
            
            return Color.rgb(r, g, b) to 255
        } else {
            // Twilight/Night: Keep alpha fully visible (255) so the sun is clearly yellow and visible.
            val ratio = ((elevation - darkestElev) / (0.0 - darkestElev)).coerceIn(0.0, 1.0)
            val alpha = 255 // Opaque yellow/gold sun at night
            
            // Transition from Deep Orange (#FF4500) at horizon to Gold/Yellow (#FFD700) at night
            val r = 255
            val g = (215 * (1 - ratio) + 69 * ratio).toInt()
            val b = 0
            
            return Color.rgb(r, g, b) to alpha
        }
    }

    private fun interpolateSunOutlineColor(elevation: Double): Int {
        val darkestElev = -8.0
        if (elevation >= 0) {
            val t = (elevation / 20.0).coerceIn(0.0, 1.0)
            // Outline stays a bit more orange/red than the fill for contrast
            val r = 255
            val g = (40 * (1 - t) + 165 * t).toInt() 
            val b = 0
            return Color.rgb(r, g, b)
        } else {
            // At night, the outline matches the fill to prevent the "ring" look
            val ratio = ((elevation - darkestElev) / (0.0 - darkestElev)).coerceIn(0.0, 1.0)
            val r = 255
            val g = (215 * (1 - ratio) + 40 * ratio).toInt()
            val b = 0
            return Color.rgb(r, g, b)
        }
    }
}
