package com.example.a24_hr_clock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.a24_hr_clock.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Tiny element sketches that mirror ClockRenderer colors (no full dial except SmallTopRight). */
private val FaceWhite = Color.White
private val FaceBlack = Color.Black
private val NightSlate = Color(0xFF2C3E50)
private val SleepPurple = Color(0xFF6A5ACD)
private val SleepNap = Color(0xFF8A7AED)
private val HandAmber = Color(0xFFFF9F1C)
private val SunGold = Color(0xFFFFD700)
private val SunOrange = Color(0xFFFFA500)
private val MoonLit = Color(0xFFE0E0E0)
private val MoonShadow = Color(0xFF444444)
/** Energy curve endpoints from ClockRenderer.interpolateEnergyColor (v=0 / v=1). */
private val EnergyMin = Color(0xFF00D2FF)
private val EnergyMax = Color(0xFFFF4B2B)

private fun hourToCanvasDegrees(hour: Double): Float = ((hour - 18.0) * 15.0).toFloat()

/**
 * Inward phase marker matching ClockRenderer: height = triangleSize,
 * base = triangleSize * 0.8, tip toward center (drawn tip-up here).
 */
private fun DrawScope.drawPhaseTriangle(color: Color) {
    // Slightly smaller than other 40.dp glyphs so the tip reads clearly in the row.
    val height = min(size.width, size.height) * 0.48f
    val base = height * 0.8f
    val halfBase = base / 2f
    val tip = Offset(size.width / 2f, (size.height - height) / 2f)
    val baseY = tip.y + height
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x + halfBase, baseY)
        lineTo(tip.x - halfBase, baseY)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.clockPoint(cx: Float, cy: Float, radius: Float, hour: Double, fraction: Float = 1f): Offset {
    val rad = Math.toRadians(hourToCanvasDegrees(hour).toDouble())
    return Offset(
        cx + radius * fraction * cos(rad).toFloat(),
        cy + radius * fraction * sin(rad).toFloat()
    )
}

@Composable
fun GlyphNumbers(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        // Every 3h in 0–23 (24h integers); roomier than even-hour 12h labels
        val c = Offset(size.width / 2f, size.height / 2f)
        val textRadius = min(size.width, size.height) * 0.38f
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.minDimension * 0.16f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val nc = drawContext.canvas.nativeCanvas
        for (h in 0 until 24 step 3) {
            val p = clockPoint(c.x, c.y, textRadius, h.toDouble())
            nc.drawText(
                h.toString(),
                p.x,
                p.y + paint.textSize / 3f,
                paint
            )
        }
    }
}

@Composable
fun GlyphSunMoon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val r = size.minDimension * 0.18f
        // Keep clear gap between sun and moon disks
        val sun = Offset(size.width * 0.26f, size.height * 0.5f)
        val moon = Offset(size.width * 0.74f, size.height * 0.5f)

        drawCircle(SunGold, r, sun)
        drawCircle(SunOrange, r, sun, style = Stroke(1.5f))

        // Match ClockRenderer.drawSunAndMoon waxing crescent (~day 4.5 / synodic month).
        drawCircle(MoonShadow, r, moon)
        val topLeft = Offset(moon.x - r, moon.y - r)
        val disc = Size(r * 2, r * 2)
        drawArc(
            color = MoonLit,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = topLeft,
            size = disc
        )
        val p = 0.15
        val midP = (p * 4) - 1
        val eWidth = abs(midP).toFloat() * r
        val ePaint = if (midP < 0) MoonShadow else MoonLit
        drawOval(
            color = ePaint,
            topLeft = Offset(moon.x - eWidth, moon.y - r),
            size = Size(eWidth * 2, r * 2)
        )
    }
}

/** Only glyph that keeps a mini dial — conveys the top-right placement. */
@Composable
fun GlyphSmallTopRight(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        drawRect(Color.Black)
        val r = min(size.width, size.height) * 0.22f
        val c = Offset(size.width - r - 3f, r + 6f)
        drawCircle(FaceWhite, r, c)
        drawArc(
            NightSlate,
            hourToCanvasDegrees(18.0),
            180f,
            true,
            Offset(c.x - r, c.y - r),
            Size(r * 2, r * 2)
        )
        drawCircle(FaceBlack, r, c, style = Stroke(1f))
        val hand = clockPoint(c.x, c.y, r, 10.0, 0.85f)
        drawLine(HandAmber, c, hand, strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

@Composable
fun GlyphLifeCalendar(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val cols = 7
        val rows = 5
        val pad = 2f
        val cellW = (size.width - pad * 2) / cols
        val cellH = (size.height - pad * 2) / rows
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val filled = row * cols + col < 18
                drawRect(
                    color = if (filled) Color(0xFF4A90D9) else Color(0xFFB0BEC5),
                    topLeft = Offset(pad + col * cellW + 0.5f, pad + row * cellH + 0.5f),
                    size = Size(cellW - 1f, cellH - 1f)
                )
            }
        }
    }
}

@Composable
fun GlyphWakeSunriseInfo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.height * 0.36f
            isAntiAlias = true
            isFakeBoldText = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        drawContext.canvas.nativeCanvas.drawText(
            "±UTC",
            size.width / 2f,
            size.height / 2f + paint.textSize / 3f,
            paint
        )
    }
}

@Composable
fun GlyphTimezoneMap(modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp)) {
        // Subtle dark plate so the light-gray continents read on any settings background
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF1A1F24))
        }
        Image(
            painter = painterResource(R.drawable.world_map_equirectangular),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier.fillMaxSize()) {
            // Amber meridian ~EST band; tiny white location mark
            val meridianX = size.width * 0.29f
            drawLine(
                HandAmber,
                Offset(meridianX, size.height * 0.08f),
                Offset(meridianX, size.height * 0.92f),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
            drawCircle(
                Color(0xFFFFFFFF),
                radius = 1.4f,
                center = Offset(size.width * 0.29f, size.height * 0.38f)
            )
        }
    }
}

@Composable
fun GlyphSleepArc(modifier: Modifier = Modifier, showTotalBedtime: Boolean = true) {
    Canvas(modifier.size(40.dp)) {
        val r = min(size.width, size.height) / 2f - 1f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = if (showTotalBedtime) SleepPurple else SleepNap,
            startAngle = hourToCanvasDegrees(23.0),
            sweepAngle = 8f * 15f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
    }
}

/** Purple sleep wedge with a red “awake” slice over the first ~15% of the sleep period. */
@Composable
fun GlyphSubtractAwake(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val r = min(size.width, size.height) / 2f - 1f
        val c = Offset(size.width / 2f, size.height / 2f)
        val start = hourToCanvasDegrees(23.0)
        val sleepSweep = 8f * 15f
        val awakeSweep = sleepSweep * 0.15f
        val topLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(
            color = SleepPurple,
            startAngle = start,
            sweepAngle = sleepSweep,
            useCenter = true,
            topLeft = topLeft,
            size = arcSize
        )
        drawArc(
            color = Color(0xFFFF6B6B),
            startAngle = start,
            sweepAngle = awakeSweep,
            useCenter = true,
            topLeft = topLeft,
            size = arcSize
        )
    }
}

@Composable
fun GlyphSleepDebtText(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF6B6B")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.height * 0.36f
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            "+1.2h",
            size.width / 2f,
            size.height / 2f + paint.textSize / 3f,
            paint
        )
    }
}

@Composable
fun GlyphWakeIndicator(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        // Match ClockRenderer wake tick at 9:00 — radial black outline + white core across rim
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.38f
        val wakeHour = 9.0
        val rad = Math.toRadians(hourToCanvasDegrees(wakeHour).toDouble())
        val tickLen = radius * 0.55f
        val halfLen = tickLen / 2f
        val outlineOffset = 1.5f

        fun pt(dist: Float) = Offset(
            c.x + dist * cos(rad).toFloat(),
            c.y + dist * sin(rad).toFloat()
        )
        val bx1 = pt(radius - halfLen - outlineOffset)
        val bx2 = pt(radius + halfLen + outlineOffset)
        val x1 = pt(radius - halfLen)
        val x2 = pt(radius + halfLen)

        drawArc(
            color = Color(0x33000000),
            startAngle = hourToCanvasDegrees(wakeHour) - 28f,
            sweepAngle = 56f,
            useCenter = false,
            topLeft = Offset(c.x - radius, c.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = 1.5f)
        )
        drawLine(FaceBlack, bx1, bx2, strokeWidth = 7f, cap = StrokeCap.Butt)
        drawLine(Color.White, x1, x2, strokeWidth = 4f, cap = StrokeCap.Butt)
    }
}

@Composable
fun GlyphBathyphase(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        drawPhaseTriangle(EnergyMin)
    }
}

@Composable
fun GlyphAcrophase(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        drawPhaseTriangle(EnergyMax)
    }
}

@Composable
fun GlyphGrogginess(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val r = min(size.width, size.height) / 2f - 1f
        val c = Offset(size.width / 2f, size.height / 2f)
        // ~6:00 AM start
        val start = hourToCanvasDegrees(6.0)
        val sweep = (1.5 * 15.0 + 0.5).toFloat()
        drawArc(
            color = Color(0xFF555555),
            startAngle = start,
            sweepAngle = sweep * 0.45f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
        drawArc(
            color = Color(0xFFAAAAAA),
            startAngle = start + sweep * 0.4f,
            sweepAngle = sweep * 0.35f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
        drawArc(
            color = Color(0xFFEEEEEE),
            startAngle = start + sweep * 0.7f,
            sweepAngle = sweep * 0.3f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
    }
}

@Composable
fun GlyphWindDown(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val r = min(size.width, size.height) / 2f - 1f
        val c = Offset(size.width / 2f, size.height / 2f)
        // ~8:00 PM (20:00) start — same dark→light gray as grogginess
        val start = hourToCanvasDegrees(20.0)
        val sweep = (1.5 * 15.0 + 0.5).toFloat()
        drawArc(
            color = Color(0xFF555555),
            startAngle = start,
            sweepAngle = sweep * 0.45f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
        drawArc(
            color = Color(0xFFAAAAAA),
            startAngle = start + sweep * 0.4f,
            sweepAngle = sweep * 0.35f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
        drawArc(
            color = Color(0xFFEEEEEE),
            startAngle = start + sweep * 0.7f,
            sweepAngle = sweep * 0.3f,
            useCenter = true,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2)
        )
    }
}

@Composable
fun GlyphBedtimeCountdown(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val ink = Color(0xFFE8EEF2)
        // Bed (headboard + mattress + pillow)
        val bedLeft = size.width * 0.08f
        val bedTop = size.height * 0.52f
        val bedW = size.width * 0.55f
        val bedH = size.height * 0.28f
        drawRect(ink, Offset(bedLeft, bedTop), Size(bedW * 0.12f, bedH))
        drawRoundRect(
            color = ink,
            topLeft = Offset(bedLeft + bedW * 0.1f, bedTop + bedH * 0.35f),
            size = Size(bedW * 0.9f, bedH * 0.55f),
            cornerRadius = CornerRadius(3f, 3f)
        )
        drawCircle(ink, bedH * 0.22f, Offset(bedLeft + bedW * 0.28f, bedTop + bedH * 0.22f))

        // Stopwatch
        val swCx = size.width * 0.72f
        val swCy = size.height * 0.38f
        val swR = size.minDimension * 0.22f
        drawCircle(ink, swR, Offset(swCx, swCy), style = Stroke(2.2f))
        drawLine(
            ink,
            Offset(swCx, swCy - swR - 1f),
            Offset(swCx, swCy - swR - 5f),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
        drawLine(
            ink,
            Offset(swCx - 4f, swCy - swR - 5f),
            Offset(swCx + 4f, swCy - swR - 5f),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
        drawLine(
            HandAmber,
            Offset(swCx, swCy),
            Offset(swCx + swR * 0.45f, swCy - swR * 0.35f),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
        drawCircle(HandAmber, 1.8f, Offset(swCx, swCy))
    }
}

@Composable
fun GlyphEnergyCurve(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        // Closed circular harmonic: r = r0 + A * sin(kθ)
        val c = Offset(size.width / 2f, size.height / 2f)
        val r0 = min(size.width, size.height) * 0.32f
        val amp = min(size.width, size.height) * 0.12f
        val k = 3.0
        val steps = 96
        var last: Offset? = null
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val theta = t * 2.0 * Math.PI
            val canvasDeg = Math.toDegrees(theta) - 90.0
            val rad = Math.toRadians(canvasDeg)
            val r = (r0 + amp * sin(k * theta)).toFloat()
            val p = Offset(
                c.x + r * cos(rad).toFloat(),
                c.y + r * sin(rad).toFloat()
            )
            val e = ((r - (r0 - amp)) / (2f * amp)).coerceIn(0f, 1f)
            val col = Color(
                red = 0.15f + 0.85f * e,
                green = 0.45f + 0.2f * (1f - e),
                blue = 1f - 0.55f * e
            )
            if (last != null) {
                drawLine(col, last, p, strokeWidth = 2.2f, cap = StrokeCap.Round)
            }
            last = p
        }
    }
}

@Composable
fun GlyphEnergyPct(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF9F1C")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.height * 0.4f
            isAntiAlias = true
            isFakeBoldText = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            "72%",
            size.width / 2f,
            size.height / 2f + paint.textSize / 3f,
            paint
        )
    }
}

@Composable
fun GlyphNormalizeEnergy(modifier: Modifier = Modifier) {
    Canvas(modifier.size(40.dp)) {
        val padX = 3f
        val baseY = size.height * 0.82f
        val peakY = size.height * 0.18f
        val midX = size.width / 2f
        val sigma = size.width * 0.18f
        val steps = 48
        var last: Offset? = null
        for (i in 0..steps) {
            val x = padX + (size.width - 2 * padX) * (i.toFloat() / steps)
            val dx = x - midX
            val g = exp(-(dx * dx) / (2f * sigma * sigma))
            val y = baseY - (baseY - peakY) * g
            val p = Offset(x, y)
            if (last != null) {
                drawLine(Color(0xFF2E7D32), last, p, strokeWidth = 2.4f, cap = StrokeCap.Round)
            }
            last = p
        }
        drawLine(
            Color(0x662E7D32),
            Offset(padX, baseY),
            Offset(size.width - padX, baseY),
            strokeWidth = 1.2f
        )
    }
}
