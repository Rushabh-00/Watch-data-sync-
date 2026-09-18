package app.watchdatasync.protocol

import java.util.Calendar
import java.util.Locale

/**
 * Protocol-family adapter for the observed FT_38093 / GloryFit-style BLE layout.
 *
 * Direct FT_38093 evidence in this project:
 * - 000055ff / 000033f1 + 000033f2
 * - 000056ff / 000034f1 + 000034f2
 * - live E5 11 00 [BPM] frames on 33f2
 *
 * The sync command set below is derived from the matching public reverse-engineered
 * protocol family. The app only enables it after the FT_38093 GATT layout is observed.
 */
class FastrackProtocol : WatchProtocol {
    override val id: String = "fastrack-ft38093"
    override val displayName: String = "Fastrack FT_38093 sync protocol"

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
            CHAR_33F1_UUID in normalizedCharacteristics &&
            CHAR_33F2_UUID in normalizedCharacteristics
    }

    data class Command(
        val label: String,
        val characteristicUuid: String,
        val payload: ByteArray,
        val writeWithoutResponse: Boolean,
        val settleDelayMs: Long = 500L,
    )

    data class DailyActivityRecord(
        val steps: Int,
        val calories: Int,
        val distanceMeters: Int,
        val activeMinutes: Int,
        val flags: Long,
    )

    data class SleepStageRecord(
        val hour: Int,
        val minute: Int,
        val stage: Int,
        val durationMinutes: Int,
    )

    fun buildAutomaticSyncCommands(now: Calendar): List<Command> {
        val sevenDaysAgo = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }

        return listOf(
            Command(
                label = "Channel 1 handshake",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("08 08 44 2A 01 24 39 43 75 6F FF FE D9 21 00 5F 78 4B E1 DC"),
                writeWithoutResponse = false,
                settleDelayMs = 700L,
            ),
            Command(
                label = "Channel 2 init",
                characteristicUuid = CHAR_34F1_UUID,
                payload = hex("00 F4 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04 02"),
                writeWithoutResponse = true,
                settleDelayMs = 700L,
            ),
            Command(
                label = "Sync watch clock",
                characteristicUuid = CHAR_33F1_UUID,
                payload = syncTimeCommand(now),
                writeWithoutResponse = false,
                settleDelayMs = 600L,
            ),
            Command(
                label = "Request serial",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("A1"),
                writeWithoutResponse = false,
                settleDelayMs = 600L,
            ),
            Command(
                label = "Request watch battery",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("A2"),
                writeWithoutResponse = false,
                settleDelayMs = 600L,
            ),
            Command(
                label = "Request watch status",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("BB"),
                writeWithoutResponse = false,
                settleDelayMs = 600L,
            ),
            Command(
                label = "Sync daily activity summary",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("26 01"),
                writeWithoutResponse = false,
                settleDelayMs = 2_000L,
            ),
            Command(
                label = "Query step and sleep status",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("AA"),
                writeWithoutResponse = false,
                settleDelayMs = 700L,
            ),
            Command(
                label = "Sync step history",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("B2 FA"),
                writeWithoutResponse = false,
                settleDelayMs = 1_000L,
            ),
            Command(
                label = "Sync sleep history",
                characteristicUuid = CHAR_33F1_UUID,
                payload = hex("31 01"),
                writeWithoutResponse = false,
                settleDelayMs = 5_000L,
            ),
            Command(
                label = "Sync heart-rate history",
                characteristicUuid = CHAR_33F1_UUID,
                payload = historyHeartRateCommand(sevenDaysAgo),
                writeWithoutResponse = false,
                settleDelayMs = 1_000L,
            ),
            Command(
                label = "Sync SpO₂ history",
                characteristicUuid = CHAR_34F1_UUID,
                payload = hex("34 FA"),
                writeWithoutResponse = true,
                settleDelayMs = 1_000L,
            ),
        )
    }

    fun decode(
        characteristicUuid: String,
        packet: ByteArray,
    ): String? {
        val uuid = characteristicUuid.lowercase(Locale.ROOT)
        if (packet.isEmpty() ||
            (uuid != CHAR_33F2_UUID && uuid != CHAR_34F2_UUID)
        ) {
            return null
        }

        val b0 = packet[0].toInt() and 0xFF

        if (
            packet.size >= 4 &&
            b0 == 0xE5 &&
            packet[1].toInt() and 0xFF == 0x11 &&
            packet[2].toInt() and 0xFF == 0x00
        ) {
            val bpm = packet[3].toInt() and 0xFF
            if (bpm in 30..220) {
                return "Heart rate $bpm bpm"
            }
            return "Heart-rate frame • raw value $bpm • no valid live BPM"
        }

        if (b0 == 0x26) {
            val activity = decodeDailyActivity(packet)
            if (activity != null) {
                return "FT_38093 daily activity • steps=" + activity.steps +
                    " • calories=" + activity.calories + " kcal" +
                    " • distance=" + activity.distanceMeters + " m" +
                    " • active=" + activity.activeMinutes + " min"
            }
        }

        if (b0 == 0x32) {
            val sleep = decodeSleepStage(packet)
            if (sleep != null) {
                return "FT_38093 sleep stage • " +
                    String.format(
                        Locale.US,
                        "%02d:%02d • stage=%d • %d min",
                        sleep.hour,
                        sleep.minute,
                        sleep.stage,
                        sleep.durationMinutes,
                    )
            }
        }

        if (b0 == 0x44) {
            return "FT_38093 vendor frame • " +
                packet.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        }

        if (packet.size == 14 && b0 == 0xEB && packet[1].toInt() and 0xFF == 0x01) {
            val year = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            val month = packet[4].toInt() and 0xFF
            val day = packet[5].toInt() and 0xFF
            val hour = packet[6].toInt() and 0xFF
            val minute = packet[7].toInt() and 0xFF
            if (
                year in 2020..2100 &&
                month in 1..12 &&
                day in 1..31 &&
                hour in 0..23 &&
                minute in 0..59
            ) {
                return "FT_38093 dated vendor record • %04d-%02d-%02d %02d:%02d • fields=%s"
                    .format(
                        Locale.US,
                        year,
                        month,
                        day,
                        hour,
                        minute,
                        packet.drop(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) },
                    )
            }
        }

        return null
    }

    fun decodeDailyActivity(packet: ByteArray): DailyActivityRecord? {
        if (packet.size < 13) return null
        if ((packet[0].toInt() and 0xFF) != 0x26 || (packet[1].toInt() and 0xFF) != 0x01) {
            return null
        }

        val flags =
            (packet[2].toLong() and 0xFFL) or
                ((packet[3].toLong() and 0xFFL) shl 8) or
                ((packet[4].toLong() and 0xFFL) shl 16) or
                ((packet[5].toLong() and 0xFFL) shl 24)
        val steps = leU16(packet, 6)
        val calories = leU16(packet, 8)
        val distanceMeters = leU16(packet, 10)
        val activeMinutes = packet[12].toInt() and 0xFF

        if (steps !in 0..100_000) return null
        if (calories !in 0..10_000) return null
        if (distanceMeters !in 0..100_000) return null
        if (activeMinutes !in 0..1_440) return null

        return DailyActivityRecord(
            steps = steps,
            calories = calories,
            distanceMeters = distanceMeters,
            activeMinutes = activeMinutes,
            flags = flags,
        )
    }

    fun decodeSleepStage(packet: ByteArray): SleepStageRecord? {
        if (packet.size < 6 || (packet.size - 1) % 5 != 0) return null
        if ((packet[0].toInt() and 0xFF) != 0x32) return null

        val offset = 1
        val hour = packet[offset].toInt() and 0xFF
        val minute = packet[offset + 1].toInt() and 0xFF
        val stage = packet[offset + 2].toInt() and 0xFF
        val durationMinutes = beU16(packet, offset + 3)

        if (
            hour !in 0..23 ||
            minute !in 0..59 ||
            stage !in 1..4 ||
            durationMinutes !in 1..720
        ) {
            return null
        }

        return SleepStageRecord(
            hour = hour,
            minute = minute,
            stage = stage,
            durationMinutes = durationMinutes,
        )
    }

    private fun leU16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or
            ((value[offset + 1].toInt() and 0xFF) shl 8)

    private fun beU16(value: ByteArray, offset: Int): Int =
        ((value[offset].toInt() and 0xFF) shl 8) or
            (value[offset + 1].toInt() and 0xFF)

    override fun describe(packet: ByteArray): String =
        "Fastrack packet: " + packet.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

    private fun syncTimeCommand(calendar: Calendar): ByteArray {
        return byteArrayOf(
            0xA3.toByte(),
            ((calendar.get(Calendar.YEAR) shr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.YEAR) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
            calendar.get(Calendar.SECOND).toByte(),
        )
    }

    private fun historyHeartRateCommand(calendar: Calendar): ByteArray {
        return byteArrayOf(
            0xF7.toByte(),
            0xFA.toByte(),
            ((calendar.get(Calendar.YEAR) shr 8) and 0xFF).toByte(),
            (calendar.get(Calendar.YEAR) and 0xFF).toByte(),
            (calendar.get(Calendar.MONTH) + 1).toByte(),
            calendar.get(Calendar.DAY_OF_MONTH).toByte(),
            calendar.get(Calendar.HOUR_OF_DAY).toByte(),
            calendar.get(Calendar.MINUTE).toByte(),
        )
    }

    private fun hex(text: String): ByteArray =
        text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SERVICE_55FF_UUID = "000055ff-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F1_UUID = "000033f1-0000-1000-8000-00805f9b34fb"
        const val CHAR_33F2_UUID = "000033f2-0000-1000-8000-00805f9b34fb"
        const val CHAR_34F1_UUID = "000034f1-0000-1000-8000-00805f9b34fb"
    }
}
