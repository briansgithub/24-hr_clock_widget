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
        color = Color.parseColor("#6A5ACD")
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
        sleepHour: Double?,
        wakeHour: Double?,
        sunRad: Double,
        moonRad: Double,
        moonPhaseValue: Double,
        solarIrradiance: Int,
        sleepDebt: Double,
        sleepDuration: Double,
        bathyphaseHour: Double?,
        showNumbers: Boolean = true,
        showSleep: Boolean = true,
        showSunMoon: Boolean = true,
        showSleepDebtText: Boolean = true,
        showEnergy: Boolean = false,
        smallTopRight: Boolean = false,
        showLifeCalendar: Boolean = false
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
            centerX = width - radius - 50f
            centerY = radius + 150f // Leave some space for status bar
        } else {
            // Large centered clock
            centerX = width / 2f
            centerY = height / 2f
            radius = min(width, height) / 2f - 100f
        }

        if (radius < 20) return

        // 1. Draw solid background
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // 2. Draw Night Shading
        drawNightShading(canvas, centerX, centerY, radius, sunriseHour, sunsetHour)

        // 3. Draw Sleep Arc
        if (showSleep && wakeHour != null && sleepHour != null) {
            drawSleepArc(canvas, centerX, centerY, radius, sleepHour, wakeHour)
        }

        // 4. Draw Energy Curve
        if (showEnergy && wakeHour != null) {
            drawEnergyCurve(canvas, centerX, centerY, radius, wakeHour, sleepDebt, sleepDuration, bathyphaseHour)
        }

        // 5. Draw face outline
        canvas.drawCircle(centerX, centerY, radius, faceOutlinePaint)

        // 6. Draw Solar Circle
        drawSolarCircle(canvas, centerX, centerY, radius, solarIrradiance)

        // 7. Draw Sleep Debt Text
        if (showSleepDebtText) {
            drawSleepDebtText(canvas, centerX, centerY, radius, sleepDebt)
        }

        // 7. Draw Ticks and Numbers
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

        // 6. Draw Sun and Moon
        if (showSunMoon) {
            drawSunAndMoon(canvas, centerX, centerY, radius, sunRad, moonRad, moonPhaseValue)
        }

        // 7. Draw Clock Hand
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
        bathyphaseHour: Double?
    ) {
        val steps = 72
        var lastX = 0f
        var lastY = 0f

        // Pre-calculate levels for normalization
        val levels = mutableListOf<Double>()
        for (i in 0..steps) {
            val h = (i.toDouble() / steps) * 24.0
            levels.add(EnergyCalculator.getEnergyLevel(h, wakeHour, sleepDebt, sleepDuration, bathyphaseHour, false))
        }
        val eMin = levels.minOrNull() ?: 0.0
        val eMax = levels.maxOrNull() ?: 1.0
        val eRange = eMax - eMin

        for (i in 0..steps) {
            val h = (i.toDouble() / steps) * 24.0
            val energy = levels[i]
            
            // Scaled radius (matching Python get_display_radius logic)
            val displayEnergy = if (eRange > 0.01) (energy - eMin) / eRange else energy.coerceIn(0.0, 1.0)
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

    private fun drawSleepArc(canvas: Canvas, cx: Float, cy: Float, radius: Float, sleepHour: Double, wakeHour: Double) {
        var duration = wakeHour - sleepHour
        if (duration < 0) duration += 24.0
        val sleepStartAngle = (sleepHour - 18.0) * 15.0
        val sleepSweep = duration * 15.0
        val margin = radius * 0.15f
        val rect = RectF(cx - (radius - margin), cy - (radius - margin), cx + (radius - margin), cy + (radius - margin))
        canvas.drawArc(rect, sleepStartAngle.toFloat(), sleepSweep.toFloat(), true, sleepArcPaint)
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
