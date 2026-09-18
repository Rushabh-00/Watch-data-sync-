package app.watchdatasync.protocol

import java.util.Calendar
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastrackProtocolTest {
    private fun calendar(): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 18, 18, 41, 8)
        }

    @Test
    fun automaticSyncContainsFamilyCommands() {
        val commands = FastrackProtocol().buildAutomaticSyncCommands(calendar())
        val payloads = commands.map { it.payload.toHex() }

        assertTrue(payloads.contains("08 08 44 2A 01 24 39 43 75 6F FF FE D9 21 00 5F 78 4B E1 DC"))
        assertTrue(payloads.contains("00 F4 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 04 02"))
        assertTrue(payloads.contains("A1"))
        assertTrue(payloads.contains("A2"))
        assertTrue(payloads.contains("26 01"))
        assertTrue(payloads.contains("B2 FA"))
        assertTrue(payloads.contains("31 01"))
        assertTrue(payloads.contains("34 FA"))
        assertTrue(payloads.indexOf("26 01") < payloads.indexOf("B2 FA"))
        assertTrue(payloads.indexOf("26 01") < payloads.indexOf("31 01"))
    }

    @Test
    fun automaticSyncBuildsCurrentClockAndSevenDayHistory() {
        val commands = FastrackProtocol().buildAutomaticSyncCommands(calendar())
        val clock = commands.first { it.label == "Sync watch clock" }.payload
        val hrHistory = commands.first { it.label == "Sync heart-rate history" }.payload

        assertArrayEquals(
            byteArrayOf(
                0xA3.toByte(), 0x07, 0xEA.toByte(), 0x09, 0x12, 0x12, 0x29, 0x08,
            ),
            clock,
        )
        assertEquals(
            "F7 FA 07 EA 09 0B 12 29",
            hrHistory.toHex(),
        )
    }

    @Test
    fun dailyActivityPacketUsesCorrectOffsets() {
        val packet = byteArrayOf(
            0x26, 0x01,
            0x00, 0xF2.toByte(), 0x8D.toByte(), 0xC1.toByte(),
            0x71, 0x17,
            0x5B, 0x01,
            0xF2.toByte(), 0x08,
            0x10,
            0x00, 0x00,
        )

        val decoded = FastrackProtocol().decodeDailyActivity(packet)

        assertEquals(6001, decoded?.steps)
        assertEquals(347, decoded?.calories)
        assertEquals(2290, decoded?.distanceMeters)
        assertEquals(16, decoded?.activeMinutes)
    }

    @Test
    fun sleepStagePacketMatchesObservedFiveByteRecordLayout() {
        val packet = byteArrayOf(
            0x32,
            0x17, 0x0F, 0x02,
            0x00, 0x0C,
        )

        val decoded = FastrackProtocol().decodeSleepStage(packet)

        assertEquals(23, decoded?.hour)
        assertEquals(15, decoded?.minute)
        assertEquals(2, decoded?.stage)
        assertEquals(12, decoded?.durationMinutes)
    }

    @Test
    fun liveHeartRateFrameIsDecoded() {
        val decoded = FastrackProtocol().decode(
            "000033f2-0000-1000-8000-00805f9b34fb",
            byteArrayOf(0xE5.toByte(), 0x11, 0x00, 0x68),
        )
        assertEquals("Heart rate 104 bpm", decoded)
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
