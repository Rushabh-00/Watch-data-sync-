package app.watchdatasync.model

data class GattCharacteristic(
    val uuid: String,
    val properties: List<String>,
)

data class GattService(
    val uuid: String,
    val characteristics: List<GattCharacteristic>,
)

data class GattValue(
    val serviceUuid: String,
    val characteristicUuid: String,
    val timestamp: String,
    val hex: String,
    val ascii: String,
    val decoded: String?,
) {
    val key: String
        get() = serviceUuid + "/" + characteristicUuid
}
