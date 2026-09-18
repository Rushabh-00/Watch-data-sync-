package app.watchdatasync.protocol

interface WatchProtocol {
    val id: String
    val displayName: String

    fun matches(
        advertisedName: String?,
        serviceUuids: Set<String>,
    ): Boolean

    fun describe(packet: ByteArray): String
}

object UnknownWatchProtocol : WatchProtocol {
    override val id: String = "unknown"
    override val displayName: String = "Unknown BLE watch"

    override fun matches(
        advertisedName: String?,
        serviceUuids: Set<String>,
    ): Boolean = false

    override fun describe(packet: ByteArray): String =
        "raw(" + packet.size + " bytes)"
}
