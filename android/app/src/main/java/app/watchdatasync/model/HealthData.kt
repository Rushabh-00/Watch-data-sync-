package app.watchdatasync.model

data class HeartRateHistorySample(
    val epochMillis: Long,
    val bpm: Int,
)

data class Spo2HistorySample(
    val epochMillis: Long,
    val percent: Int,
)

data class DailyActivitySummary(
    val epochMillis: Long,
    val steps: Int,
    val calories: Int,
    val distanceMeters: Int,
    val activeMinutes: Int,
)

data class SleepStageSample(
    val epochMillis: Long,
    val stage: Int,
    val durationMinutes: Int,
)



data class StepHistorySample(
    val epochMillis: Long,
    val totalSteps: Int,
)
