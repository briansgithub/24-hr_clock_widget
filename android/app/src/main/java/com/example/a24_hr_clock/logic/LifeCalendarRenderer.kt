package com.example.a24_hr_clock.logic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

data class LifePhase(
    val name: String,
    val startAge: Int,
    val startWeek: Int = 0,
    val endAge: Int,
    val endWeek: Int = 51,
    val color: Int
)

data class LifeMilestone(
    val age: Int,
    val week: Int,
    val label: String,
    val color: Int
)

class LifeCalendarRenderer {
    private val birthdate = LocalDate.of(1995, 1, 20)
    private val lifespanYears = 80
    
    private val phases = listOf(
        LifePhase("Early Childhood", 0, 0, 5, 51, Color.parseColor("#5B8FB9")),
        LifePhase("K-8 Education", 6, 0, 14, 33, Color.parseColor("#7FB77E")),
        LifePhase("BTHS", 14, 34, 18, 33, Color.parseColor("#B19CD9")),
        LifePhase("Rutgers University", 18, 34, 23, 18, Color.parseColor("#FFD8A8")),
        LifePhase("ETI", 23, 23, 25, 8, Color.parseColor("#4ECDC4")),
        LifePhase("Huntsville", 27, 16, 29, 48, Color.parseColor("#FFFF00")),
        LifePhase("Career / Prime", 23, 19, 65, 51, Color.parseColor("#F9E076")),
        LifePhase("Retirement", 66, 0, 90, 51, Color.parseColor("#B05C91"))
    )
    
    private val milestones = listOf(
        LifeMilestone(18, 1, "Turned 18", Color.parseColor("#404040")),
        LifeMilestone(18, 21, "Graduated BTHS", Color.parseColor("#0077BE")),
        LifeMilestone(23, 19, "Graduated Rutgers", Color.parseColor("#CC0033")),
        LifeMilestone(30, 1, "Turned 30", Color.parseColor("#2ECC71")),
        LifeMilestone(66, 1, "Retirement", Color.parseColor("#B05C91"))
    )

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        textAlign = Paint.Align.CENTER
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val today = LocalDate.now()
        val totalWeeksLived = ChronoUnit.WEEKS.between(birthdate, today).toInt()
        val currentYear = totalWeeksLived / 52
        val currentWeek = totalWeeksLived % 52

        // Grid layout parameters
        val cols = 52
        val rows = lifespanYears
        
        val leftMargin = width * 0.05f
        val rightMargin = width * 0.15f
        val topMargin = height * 0.15f
        val bottomMargin = height * 0.15f
        
        val gridW = width - leftMargin - rightMargin
        val gridH = height - topMargin - bottomMargin
        
        val gap = 2f
        val cellSize = Math.min(
            (gridW - gap * (cols - 1)) / cols,
            (gridH - gap * (rows - 1)) / rows
        )
        
        val actualGridW = cols * cellSize + (cols - 1) * gap
        val actualGridH = rows * cellSize + (rows - 1) * gap
        
        val x0 = (width - actualGridW) / 2
        val y0 = topMargin + (gridH - actualGridH) / 2

        // Draw cells
        for (yr in 0 until rows) {
            for (wk in 0 until cols) {
                val cellIdx = yr * 52 + wk
                val isPast = cellIdx < totalWeeksLived
                val isCurrent = yr == currentYear && wk == currentWeek
                
                val phase = getPhase(yr, wk)
                val milestone = getMilestone(yr, wk)
                
                val x = x0 + wk * (cellSize + gap)
                val y = y0 + yr * (cellSize + gap)
                val rect = RectF(x, y, x + cellSize, y + cellSize)
                
                if (isCurrent) {
                    cellPaint.color = Color.WHITE
                    cellPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(rect, 2f, 2f, cellPaint)
                } else if (milestone != null) {
                    cellPaint.color = milestone.color
                    cellPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(rect, 2f, 2f, cellPaint)
                } else if (isPast) {
                    cellPaint.color = phase?.color ?: Color.parseColor("#1C1C1C")
                    cellPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(rect, 2f, 2f, cellPaint)
                } else {
                    // Future
                    cellPaint.color = Color.parseColor("#1C1C1C")
                    cellPaint.style = Paint.Style.FILL
                    canvas.drawRoundRect(rect, 2f, 2f, cellPaint)
                    
                    val phaseColor = phase?.color ?: Color.parseColor("#2A2A2A")
                    // If it's the retirement phase, keep the purple color. Otherwise, use gray.
                    borderPaint.color = if (phase?.name == "Retirement") {
                        phaseColor
                    } else {
                        Color.parseColor("#2A2A2A") 
                    }
                    canvas.drawRoundRect(rect, 2f, 2f, borderPaint)
                }
            }
        }
        
        // Draw year labels every 5 years
        textPaint.textSize = cellSize * 2f
        textPaint.textAlign = Paint.Align.RIGHT
        for (yr in 0 until rows step 5) {
            val y = y0 + yr * (cellSize + gap) + cellSize
            canvas.drawText(yr.toString(), x0 - 10f, y, textPaint)
        }
    }

    private fun getPhase(year: Int, week: Int): LifePhase? {
        val cellIdx = year * 52 + week
        for (p in phases) {
            val startIdx = p.startAge * 52 + p.startWeek
            val endIdx = p.endAge * 52 + p.endWeek
            if (cellIdx in startIdx..endIdx) return p
        }
        return null
    }

    private fun getMilestone(year: Int, week: Int): LifeMilestone? {
        return milestones.find { it.age == year && it.week == week + 1 }
    }
}
