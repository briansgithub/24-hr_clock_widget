package com.example.a24_hr_clock.logic

enum class MetricHelpId {
    HRSS,
    TRIMP,
    HRV
}

enum class MetricHelpLevel {
    ELEMENTARY,
    INTERMEDIATE,
    ADVANCED
}

data class MetricHelpEntry(
    val id: MetricHelpId,
    val shortName: String,
    val fullName: String,
    val elementary: String,
    val intermediate: String,
    val advanced: String
) {
    fun textFor(level: MetricHelpLevel): String = when (level) {
        MetricHelpLevel.ELEMENTARY -> elementary
        MetricHelpLevel.INTERMEDIATE -> intermediate
        MetricHelpLevel.ADVANCED -> advanced
    }
}

object MetricHelpContent {
    val entries: List<MetricHelpEntry> = listOf(
        MetricHelpEntry(
            id = MetricHelpId.HRSS,
            shortName = "HRSS",
            fullName = "Heart Rate Stress Score",
            elementary = "HRSS is how hard today’s training was compared with a typical week for you, shown as a percent.",
            intermediate = "HRSS (Heart Rate Stress Score) turns your daily training load into a percentage of your recent weekly average. " +
                "About 100% means a normal day for you; well above 100% means a heavier-than-usual day; well below means a lighter one. " +
                "The Exercise chart uses HRSS as cyan bars so you can see load rise and fall across the week alongside recovery (HRV).",
            advanced = "In this app, HRSS is a relative load index: HRSS = (daily TRIMP / weekly average TRIMP) × 100. " +
                "It is not an absolute physiological threshold score; it normalizes Banister-style Training Impulse against your recent weekly mean so day-to-day spikes are comparable. " +
                "Readiness alerts treat high HRSS (for example >120) together with suppressed HRV as an overreaching signal, reflecting the classic load–recovery coupling used in endurance monitoring."
        ),
        MetricHelpEntry(
            id = MetricHelpId.TRIMP,
            shortName = "TRIMP",
            fullName = "Training Impulse",
            elementary = "TRIMP is a score for how much heart-rate “work” you did in a session—the harder and longer you go, the higher it gets.",
            intermediate = "TRIMP (Training Impulse) estimates training dose from heart rate. Minutes spent only a little above your resting heart rate add a little; " +
                "time near max effort adds a lot more. Higher TRIMP means a bigger training stress that day. " +
                "In this tab, TRIMP is shown when you scrub the chart; HRSS is basically that day’s TRIMP compared with your weekly average.",
            advanced = "This app computes Banister-style TRIMP by summing, for each heart-rate sample above resting HR (RHR), " +
                "w · e^(k·w) where w = (HR − RHR) / (HRmax − RHR) and k = 1.92 (male coefficient used here). " +
                "The exponential weighting emphasizes high-intensity fractions of reserve. Daily TRIMP feeds HRSS via division by the weekly mean TRIMP; " +
                "it is the absolute impulse, while HRSS is the relative percentage of that impulse."
        ),
        MetricHelpEntry(
            id = MetricHelpId.HRV,
            shortName = "HRV",
            fullName = "Heart Rate Variability",
            elementary = "HRV is how much the time between heartbeats changes; higher usually means your body is more recovered.",
            intermediate = "HRV (Heart Rate Variability) measures small differences between successive heartbeats (in milliseconds from Fitbit). " +
                "When you are well recovered, beat-to-beat timing often varies more; when stressed or depleted, HRV often falls. " +
                "This app compares your HRV to your “HRV Medicated Base” to label the day as depleted, steady, optimized, or overreaching when load is also high.",
            advanced = "HRV here is Fitbit-reported beat-to-beat variability in milliseconds, used as a practical proxy for autonomic (especially parasympathetic) recovery status. " +
                "Alert logic compares current HRV to the configured medicated baseline: below baseline − 5 ms → depleted; above baseline + 5 ms → optimized; " +
                "HRSS > 120 with HRV at or below baseline → overreaching; otherwise steady. " +
                "Chart dashed lines mark your peak potential and medicated base so absolute HRV can be read against personal anchors rather than population norms alone."
        )
    )

    fun entry(id: MetricHelpId): MetricHelpEntry = entries.first { it.id == id }
}
