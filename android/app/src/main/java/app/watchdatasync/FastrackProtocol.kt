package app.watchdatasync

import java.util.Calendar

data class WatchBattery(
    val percent: Int,
    val charging: Boolean?,
)

object FastrackProtocol {
    const val SERVICE_UUID = "000055ff-0000-1000-8000-00805f9b34fb"
    const val TIME_WRITE_UUID = "000033f1-0000-1000-8000-00805f9b34fb"
    const val LIVE_DATA_UUID = "000033f2-0000-1000-8000-00805f9b34fb"

    fun buildBatteryRequestPacket(): ByteArray =
        byteArrayOf(0xA2.toByte())

    fun buildTimeSyncPacket(calendar: Calendar): ByteArray = byteArrayOf(
        0xA3.toByte(),
        ((calendar.get(Calendar.YEAR) shr 8) and 0xFF).toByte(),
        (calendar.get(Calendar.YEAR) and 0xFF).toByte(),
        (calendar.get(Calendar.MONTH) + 1).toByte(),
        calendar.get(Calendar.DAY_OF_MONTH).toByte(),
        calendar.get(Calendar.HOUR_OF_DAY).toByte(),
        calendar.get(Calendar.MINUTE).toByte(),
        calendar.get(Calendar.SECOND).toByte(),
    )

    fun buildDynamicHeartRateModePacket(): ByteArray =
        byteArrayOf(0xD6.toByte(), 0x02)

    fun buildLiveHeartRateStartPacket(): ByteArray =
        byteArrayOf(0xE5.toByte(), 0x11)

    fun decodeBattery(packet: ByteArray): WatchBattery? {
        if (packet.size < 2) return null
        if ((packet[0].toInt() and 0xFF) != 0xA2) return null

        val percent = packet[1].toInt() and 0xFF
        if (percent !in 0..100) return null

        val charging = packet
            .getOrNull(2)
            ?.let { (it.toInt() and 0xFF) == 0x01 }

        return WatchBattery(percent, charging)
    }

    fun decodeLiveHeartRate(packet: ByteArray): Int? {
        if (packet.size < 4) return null
        if ((packet[0].toInt() and 0xFF) != 0xE5) return null
        if ((packet[1].toInt() and 0xFF) != 0x11) return null
        if ((packet[2].toInt() and 0xFF) != 0x00) return null
        val bpm = packet[3].toInt() and 0xFF
        return bpm.takeIf { it in 30..220 }
    }

    fun matchesService(uuid: String): Boolean = uuid.equals(SERVICE_UUID, ignoreCase = true)
    fun isTargetName(name: String?): Boolean = name?.startsWith("FT_38093", ignoreCase = true) == true
}
