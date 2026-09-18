package app.watchdatasync.protocol

/**
 * Structural decoder for vendor-history packets observed in complete FT_38093
 * companion-app sync captures.
 *
 * The fields exposed here are deliberately semantic-neutral. The capture proves
 * the framing, dates/times, record widths and completion markers below, but it
 * does not prove what the record type/value fields mean.
 */
object VendorHistoryProtocol {
    sealed interface Packet {
        val raw: ByteArray
    }

    data class EcDateMarker(
        val year: Int,
        val month: Int,
        val day: Int,
        val marker: Int,
        override val raw: ByteArray,
    ) : Packet

    data class EcRecord(
        val hour: Int,
        val minute: Int,
        val type: Int,
        val reservedHigh: Int,
        val reservedLow: Int,
        val value: Int,
    )

    data class EcBatch(
        val records: List<EcRecord>,
        override val raw: ByteArray,
    ) : Packet

    data class FaSample(
        val index: Int,
        val valueA: Int,
        val valueB: Int,
        val flags: Int,
    )

    data class FaPage(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val samples: List<FaSample>,
        override val raw: ByteArray,
    ) : Packet

    data class FaCompletion(
        val marker: Int,
        override val raw: ByteArray,
    ) : Packet

    data class TransferCompletion(
        override val raw: ByteArray,
    ) : Packet

    fun decode(hex: String): Packet? = decode(hexToBytes(hex))

    fun decode(packet: ByteArray): Packet? {
        if (packet.isEmpty()) return null

        val b0 = packet[0].u8()
        val b1 = packet.getOrNull(1)?.u8() ?: return null

        return when {
            b0 == 0xEC && b1 == 0x01 -> decodeEcDateMarker(packet)
            b0 == 0xEC && b1 == 0x02 -> decodeEcBatch(packet)
            b0 == 0x44 && b1 == 0xFA -> decodeFa(packet)
            packet.contentEquals(byteArrayOf(0x52, 0x00, 0xFD.toByte())) ->
                TransferCompletion(packet.copyOf())
            else -> null
        }
    }

    fun describe(packet: Packet): String = when (packet) {
        is EcDateMarker ->
            "FT_38093 EC timeline date marker • %04d-%02d-%02d • marker=0x%02X"
                .format(packet.year, packet.month, packet.day, packet.marker)

        is EcBatch ->
            "FT_38093 EC timeline batch • records=${packet.records.size} • raw fields unclassified"

        is FaPage ->
            "FT_38093 44 FA history page • %04d-%02d-%02d %02d:%02d • samples=${packet.samples.size} • fields unclassified"
                .format(
                    packet.year,
                    packet.month,
                    packet.day,
                    packet.hour,
                    packet.minute,
                    packet.samples.size,
                )

        is FaCompletion ->
            "FT_38093 44 FA transfer marker • marker=0x%02X".format(packet.marker)

        is TransferCompletion ->
            "FT_38093 vendor transfer completion • 52 00 FD"
    }

    private fun decodeEcDateMarker(packet: ByteArray): EcDateMarker? {
        if (packet.size != 7) return null

        val year = beU16(packet, 2)
        val month = packet[4].u8()
        val day = packet[5].u8()
        val marker = packet[6].u8()

        if (!validDate(year, month, day)) return null

        return EcDateMarker(
            year = year,
            month = month,
            day = day,
            marker = marker,
            raw = packet.copyOf(),
        )
    }

    private fun decodeEcBatch(packet: ByteArray): EcBatch? {
        if (packet.size < 8 || (packet.size - 2) % 6 != 0) return null

        val records = mutableListOf<EcRecord>()
        for (offset in 2 until packet.size step 6) {
            val hour = packet[offset].u8()
            val minute = packet[offset + 1].u8()
            val type = packet[offset + 2].u8()
            val reservedHigh = packet[offset + 3].u8()
            val reservedLow = packet[offset + 4].u8()
            val value = packet[offset + 5].u8()

            if (hour !in 0..23 || minute !in 0..59) return null

            records += EcRecord(
                hour = hour,
                minute = minute,
                type = type,
                reservedHigh = reservedHigh,
                reservedLow = reservedLow,
                value = value,
            )
        }

        return if (records.isEmpty()) {
            null
        } else {
            EcBatch(
                records = records,
                raw = packet.copyOf(),
            )
        }
    }

    private fun decodeFa(packet: ByteArray): Packet? {
        if (packet.size == 4 && packet[2].u8() == 0xFD) {
            return FaCompletion(
                marker = packet[3].u8(),
                raw = packet.copyOf(),
            )
        }

        // Complete data pages observed in the capture are 44 bytes:
        // 44 FA + yyyy + MM + DD + HH + mm + 12 x 3-byte samples.
        if (packet.size != 44) return null

        val year = beU16(packet, 2)
        val month = packet[4].u8()
        val day = packet[5].u8()
        val hour = packet[6].u8()
        val minute = packet[7].u8()

        if (!validDate(year, month, day) || hour !in 0..23 || minute !in 0..59) {
            return null
        }

        val samples = buildList {
            for (index in 0 until 12) {
                val offset = 8 + index * 3
                add(
                    FaSample(
                        index = index,
                        valueA = packet[offset].u8(),
                        valueB = packet[offset + 1].u8(),
                        flags = packet[offset + 2].u8(),
                    ),
                )
            }
        }

        return FaPage(
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            samples = samples,
            raw = packet.copyOf(),
        )
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean =
        year in 2020..2100 && month in 1..12 && day in 1..31

    private fun beU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].u8() shl 8) or bytes[offset + 1].u8()

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun hexToBytes(text: String): ByteArray {
        val clean = text.trim()
        if (clean.isBlank()) return byteArrayOf()
        val parts = clean.split(Regex("\\s+"))
        if (parts.any { it.length != 2 || it.toIntOrNull(16) == null }) return byteArrayOf()
        return parts.map { it.toInt(16).toByte() }.toByteArray()
    }
}
