package com.example.a24_hr_clock.wallpaper

import android.graphics.*
import android.text.TextPaint
import com.example.a24_hr_clock.logic.EnergyCalculator
import java.util.*
import kotlin.math.*

class ClockRenderer {

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
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
        isPreview: Boolean = false,
        previewIsLockScreen: Boolean = false
    ) {
        // Clear background to OLED black
        canvas.drawColor(Color.BLACK)

        if (showLifeCalendar) {
            lifeCalendarRenderer.draw(canvas, width, height)
        }

        val centerX: Float
        val centerY: Float
        val radius: Float

        if (smallTopRight) {
            // Small clock in top right
            radius = min(width, height) / 6f
            centerX = width - radius - 80f
            centerY = radius + 150f // Leave some space for status bar
        } else {
            // Large centered clock
            centerX = width / 2f
            centerY = height / 2f
            radius = min(width, height) / 2f - 150f
        }

        if (radius < 20) return

        // 1. Draw solid background
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // 2. Draw Night Shading
        drawNightShading(canvas, centerX, centerY, radius, sunriseHour, sunsetHour)

        // 3. Draw Sleep Arc
        if (showSleep && sleepLogs.isNotEmpty()) {
            drawSleepArcs(canvas, centerX, centerY, radius, sleepLogs, includeNaps, showTotalBedtime)
        }

        // 4. Draw Calendar Events
        if (showCalendar) {
            drawCalendarEvents(canvas, centerX, centerY, radius, calendarEvents)
        }

        // 5. Draw Energy Curve
        // Identify Active Sleep Date Logs (Today or Fallback)
        val todayStr = java.time.LocalDate.now().toString()
        var activeLogs = sleepLogs.filter { it.dateOfSleep == todayStr }
        if (activeLogs.isEmpty()) {
            val lastLog = sleepLogs.maxByOrNull { it.dateOfSleep }
            if (lastLog != null) {
                activeLogs = sleepLogs.filter { it.dateOfSleep == lastLog.dateOfSleep }
            }
        }

        val mainSleep = activeLogs.find { it.isMainSleep } ?: activeLogs.maxByOrNull { it.endTime }
        
        var wakeHour = mainSleep?.let {
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

        // 10. Draw Sun and Moon
        if (showSunMoon) {
            drawSunAndMoon(canvas, centerX, centerY, radius, sunRad, moonRad, moonPhaseValue)
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
            
            drawEnergyPct(canvas, centerX, centerY, radius, exactHour, handRad, wakeHour, sleepDebt, totalAsleep, bathyphaseHour, tauWake, tauSleep, tauInertia, debtFactor, circadianOffset, useBathyphase, bedtimeGoal)
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
        bedtimeGoal: Double
    ) {
        val currentE = EnergyCalculator.getEnergyLevel(
            exactHour, wakeHour, sleepDebt, sleepDuration,
            if (useBathyphase) bathyphaseHour else null,
            false, tauWake, tauSleep, tauInertia, debtFactor,
            if (useBathyphase) null else circadianOffset
        )

        // Max possible today if rested
        val maxE = EnergyCalculator.twoProcessEnergy(
            12.0, 0.0, 9.75, 10.0, false, 
            tauWake, tauSleep, tauInertia, debtFactor
        )

        val pct = if (maxE > 0) (currentE / maxE * 100).roundToInt().coerceAtLeast(0) else 0
        
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

    private fun drawSleepDebtText(canvas: Canvas, cx: Float, cy: Float, radius: Float, debt: Double) {
        val textDist = radius * 0.5f
        // Midnight (0h) is at the bottom (90 degrees in Android coordinate system)
        val rad = Math.toRadians(90.0)
        val tx = cx + textDist * cos(rad).toFloat()
        val ty = cy + textDist * sin(rad).toFloat()

        textPaint.textSize = radius * 0.12f
        // Using a light color for better contrast against dark shading/night
        textPaint.color = Color.parseColor("#EEEEEE")
        
        val debtInt = debt.roundToInt()
        canvas.drawText("${debtInt}h", tx, ty, textPaint)
        
        textPaint.textSize = radius * 0.07f
        canvas.drawText("Debt", tx, ty + textPaint.textSize * 1.5f, textPaint)
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
        logs: List<com.example.a24_hr_clock.logic.SleepLogEntry>,
        includeNaps: Boolean,
        showTotalBedtime: Boolean
    ) {
        val todayStr = java.time.LocalDate.now().toString()
        var targetLogs = logs.filter { it.dateOfSleep == todayStr }
        
        if (targetLogs.isEmpty()) {
            val lastLog = logs.maxByOrNull { it.dateOfSleep }
            if (lastLog != null) {
                targetLogs = logs.filter { it.dateOfSleep == lastLog.dateOfSleep }
            }
        }
        
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

    private fun drawSunAndMoon(canvas: Canvas, cx: Float, cy: Float, radius: Float, sunRad: Double, moonRad: Double, moonPhase: Double) {
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
}
