package app.watchdatasync.model

data class GattCharacteristic(
    val uuid: String,
    val properties: List<String>,
)

data class GattService(
    val uuid: String,
    val characteristics: List<GattCharacteristic>,
)
