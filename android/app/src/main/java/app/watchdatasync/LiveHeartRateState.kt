package app.watchdatasync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveHeartRateSnapshot(
    val bpm: Int? = null,
    val averageBpm: Int? = null,
    val minimumBpm: Int? = null,
    val maximumBpm: Int? = null,
    val graph: List<Int> = emptyList(),
    val connected: Boolean = false,
    val timeSynced: Boolean = false,
    val deviceName: String? = null,
    val status: String = "Ready",
)

object LiveHeartRateState {
    private val _snapshot = MutableStateFlow(LiveHeartRateSnapshot())
    val snapshot = _snapshot.asStateFlow()

    fun set(value: LiveHeartRateSnapshot) {
        _snapshot.value = value
    }

    fun resetForConnection(name: String?) {
        _snapshot.value = LiveHeartRateSnapshot(
            deviceName = name,
            status = "Connecting…",
        )
    }
}
