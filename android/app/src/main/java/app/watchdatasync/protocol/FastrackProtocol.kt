package app.watchdatasync.protocol

import java.util.Locale

/**
 * Decoder for the BLE protocol family observed on the user's FT_38093 watch.
 *
 * Evidence used by this adapter:
 * - service 000055ff-0000-1000-8000-00805f9b34fb
 * - characteristic 000033f1-0000-1000-8000-00805f9b34fb (WRITE / READ)
 * - characteristic 000033f2-0000-1000-8000-00805f9b34fb (NOTIFY)
 * - live notification frame E5 11 00 [BPM] on 000033f2
 *
 * Only fields with an observed packet layout are decoded here.
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

        /*
         * The screenshots show the 55ff service plus the 33f1/33f2 channel.
         * Other discovered services use a different 3c17-d293-8e48-14fe2e4da212
         * base and are not required to identify the live heart-rate channel.
         */
        return SERVICE_55FF_UUID in normalizedServices &&
            CHAR_33F1_UUID in normalizedCharacteristics &&
            CHAR_33F2_UUID in normalizedCharacteristics
    }

    fun decode(
        characteristicUuid: String,
        packet: ByteArray,
    ): String? {
        val uuid = characteristicUuid.lowercase(Locale.ROOT)
        if (uuid != CHAR_33F2_UUID || packet.size < 4) {
            return null
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

    private companion object {
        const val SERVICE_55FF_UUID = "000055ff-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F1_UUID = "000033f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F2_UUID = "000033f2-0000-1000-8000-00805f9b34fb"
    }
}
