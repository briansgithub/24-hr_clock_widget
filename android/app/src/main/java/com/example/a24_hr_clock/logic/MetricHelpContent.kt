package com.example.a24_hr_clock.logic

enum class MetricHelpId {
    CARDIO_LOAD,
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
            id = MetricHelpId.CARDIO_LOAD,
            shortName = "Cardio Load",
            fullName = "Cardiovascular Load vs Baseline",
            elementary = "This shows how hard today's activities were compared to your usual daily average.",
            intermediate = "Cardio Load compares your daily heart-rate based effort (TRIMP) to your rolling 28-day average. 100% means a typical day for you.",
            advanced = "Calculated as (Daily TRIMP / 28-day valid mean TRIMP) × 100. This relative index normalizes Banister-style Training Impulse against your recent history to identify load spikes. It is a wellness heuristic, not a clinical measure."
        ),
        MetricHelpEntry(
            id = MetricHelpId.TRIMP,
            shortName = "TRIMP",
            fullName = "Training Impulse",
            elementary = "TRIMP is a score for your heart-rate 'work'—the harder and longer you go, the higher it gets.",
            intermediate = "TRIMP estimates training dose by weighting time spent in higher heart rate zones more heavily than time at rest.",
            advanced = "Computed by summing samples of intensity: w · e^(k·w) where w is the fraction of heart rate reserve and k is a sex-specific coefficient (1.92 for males, 1.67 for females). It represents the absolute cardiovascular dose."
        ),
        MetricHelpEntry(
            id = MetricHelpId.HRV,
            shortName = "HRV",
            fullName = "Heart Rate Variability",
            elementary = "HRV measures the variation between heartbeats. A trend in your usual range often suggests better recovery.",
            intermediate = "HRV (Heart Rate Variability) is interpreted here as a trend relative to your personal 7-day rolling range and baseline, not as a population norm.",
            advanced = "We use Fitbit's daily RMSSD. Recommendations are based on how your 7-day HRV trend aligns with your longer baseline. Significant drops may suggest recovery signals are below your usual range. This is for general wellness guidance and not for medical diagnosis."
        )
    )

    fun entry(id: MetricHelpId): MetricHelpEntry = entries.first { it.id == id }
}
