package app.watchdatasync.model

data class WatchDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bonded: Boolean,
)
