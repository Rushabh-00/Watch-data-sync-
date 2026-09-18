package app.watchdatasync.protocol

import java.util.Locale

/**
 * Decoder for the BLE protocol family observed on the user's FT_38093 watch.
 *
 * Evidence used by this adapter:
 * - vendor service 000055ff-... with 000033f1 write / 000033f2 notify
 * - vendor service 000056ff-... with 000034f1 / 000034f2
 * - vendor service 000060ff-... with 00006001 / 00006002
 * - live notification frame E5 11 00 [BPM] on 000033f2
 *
 * Only fields with an observed/corroborated packet layout are decoded here.
 */
class FastrackProtocol : WatchProtocol {
    override val id: String = "fastrack-ft38093"
    override val displayName: String = "Fastrack FT_38093 live protocol"

    override fun matches(
        advertisedName: String?,
        serviceUuids: Set<String>,
    ): Boolean = serviceUuids.any {
        it.lowercase(Locale.ROOT) == SERVICE_55FF_UUID
    }

    fun matchesGatt(
        serviceUuids: Set<String>,
        characteristicUuids: Set<String>,
    ): Boolean {
        val normalizedServices = serviceUuids.map { it.lowercase(Locale.ROOT) }.toSet()
        val normalizedCharacteristics =
            characteristicUuids.map { it.lowercase(Locale.ROOT) }.toSet()

        return SERVICE_55FF_UUID in normalizedServices &&
            SERVICE_56FF_UUID in normalizedServices &&
            SERVICE_60FF_UUID in normalizedServices &&
            CHAR_33F1_UUID in normalizedCharacteristics &&
            CHAR_33F2_UUID in normalizedCharacteristics &&
            CHAR_34F1_UUID in normalizedCharacteristics &&
            CHAR_34F2_UUID in normalizedCharacteristics &&
            CHAR_6001_UUID in normalizedCharacteristics &&
            CHAR_6002_UUID in normalizedCharacteristics
    }

    fun decode(
        characteristicUuid: String,
        packet: ByteArray,
    ): String? {
        val uuid = characteristicUuid.lowercase(Locale.ROOT)
        if (uuid != CHAR_33F2_UUID || packet.size < 4) {
            return decodeBattery(packet, uuid)
        }

        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        val b2 = packet[2].toInt() and 0xFF

        if (b0 == 0xE5 && b1 == 0x11 && b2 == 0x00) {
            val bpm = packet[3].toInt() and 0xFF
            if (bpm in 30..220) {
                return "Heart rate $bpm bpm"
            }
        }

        return null
    }

    override fun describe(packet: ByteArray): String =
        "Fastrack packet: " + packet.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

    private fun decodeBattery(packet: ByteArray, characteristicUuid: String): String? {
        if (characteristicUuid != CHAR_33F2_UUID || packet.size < 2) {
            return null
        }

        val opcode = packet[0].toInt() and 0xFF
        if (opcode != 0xA2) {
            return null
        }

        val percent = packet[1].toInt() and 0xFF
        return percent.takeIf { it in 0..100 }?.let {
            "Battery $it%"
        }
    }

    private companion object {
        const val BASE_UUID = "0000%s-3c17-d293-8e48-14fe2e4da212"
        const val SERVICE_55FF_UUID = "000055ff-0000-1000-8000-00805f9b34fb"
        const val SERVICE_56FF_UUID = "000056ff-0000-1000-8000-00805f9b34fb"
        const val SERVICE_60FF_UUID = "000060ff-0000-1000-8000-00805f9b34fb"

        const val CHAR_33F1_UUID = "000033f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F2_UUID = "000033f2-0000-1000-8000-00805f9b34fb"
        const val CHAR_34F1_UUID = "000034f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_34F2_UUID = "000034f2-0000-1000-8000-00805f9b34fb"
        const val CHAR_6001_UUID = "00006001-0000-1000-8000-00805f9b34fb"
        const val CHAR_6002_UUID = "00006002-0000-1000-8000-00805f9b34fb"
    }
}
