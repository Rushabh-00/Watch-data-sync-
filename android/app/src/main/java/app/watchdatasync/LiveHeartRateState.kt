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
    val connected: Boolean = false,
    val timeSynced: Boolean = false,
    val deviceName: String? = null,
    val status: String = "Ready",
    val overlayVisible: Boolean = false,
    val overlayLocked: Boolean = false,
    val overlayScale: Float = 1f,
    val notificationEnabled: Boolean = true,
    val keepLiveHrWhenScreenOff: Boolean = true,
)

object LiveHeartRateState {
    private val _snapshot = MutableStateFlow(LiveHeartRateSnapshot())
    val snapshot = _snapshot.asStateFlow()

    fun set(value: LiveHeartRateSnapshot) {
        _snapshot.value = value
    }
}
