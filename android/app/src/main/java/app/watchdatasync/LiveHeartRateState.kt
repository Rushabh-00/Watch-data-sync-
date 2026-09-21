package app.watchdatasync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeartRatePoint(
    val timestamp: Long,
    val bpm: Int,
)

data class LiveHeartRateSnapshot(
    val bpm: Int? = null,
    val averageBpm: Int? = null,
    val minimumBpm: Int? = null,
    val maximumBpm: Int? = null,
    val graph: List<HeartRatePoint> = emptyList(),
    val longGraph: List<HeartRatePoint> = emptyList(),
    val connected: Boolean = false,
    val deviceName: String? = null,
    val status: String = "Ready",
    val overlayVisible: Boolean = false,
    val overlayLocked: Boolean = false,
    val overlayScale: Float = 1f,
    val backgroundMonitoringEnabled: Boolean = true,
    val notificationEnabled: Boolean = true,
    val lowBatteryAlertEnabled: Boolean = true,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val rssi: Int? = null,
)

fun signalQualityForRssi(rssi: Int?): String = when {
    rssi == null -> "Signal —"
    rssi >= -55 -> "Excellent"
    rssi >= -67 -> "Good"
    rssi >= -80 -> "Fair"
    else -> "Weak"
}

object LiveHeartRateState {
    private val _snapshot = MutableStateFlow(LiveHeartRateSnapshot())
    private val _liveBpm = MutableStateFlow<Int?>(null)
    private val _liveBpmAt = MutableStateFlow<Long?>(null)

    val snapshot = _snapshot.asStateFlow()
    val liveBpm = _liveBpm.asStateFlow()
    val liveBpmAt = _liveBpmAt.asStateFlow()

    fun set(value: LiveHeartRateSnapshot) {
        _snapshot.value = value
    }

    fun setLiveBpm(value: Int?, atMillis: Long = System.currentTimeMillis()) {
        _liveBpm.value = value
        _liveBpmAt.value = value?.let { atMillis }
    }
}
