package com.example.a24_hr_clock.wallpaper

import android.content.Context

/** Density-scaled wallpaper insets so the dial clears the status bar on each phone. */
data class ClockInsets(
    val topPx: Float,
    val sidePx: Float,
    val dialMarginPx: Float
)

fun clockInsets(context: Context): ClockInsets {
    val density = context.resources.displayMetrics.density
    val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    val statusBarPx = if (statusBarId > 0) {
        context.resources.getDimensionPixelSize(statusBarId).toFloat()
    } else {
        24f * density
    }
    return ClockInsets(
        topPx = statusBarPx + 8f * density,
        sidePx = 24f * density,
        dialMarginPx = 48f * density
    )
}
