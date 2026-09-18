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
        assertTrue(payloads.contains("B2 FA"))
        assertTrue(payloads.contains("31 01"))
        assertTrue(payloads.contains("34 FA"))
        assertTrue(payloads.contains("B2 FA"))
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
