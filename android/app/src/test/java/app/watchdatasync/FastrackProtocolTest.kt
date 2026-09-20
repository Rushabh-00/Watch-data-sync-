package app.watchdatasync

import java.util.Calendar
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FastrackProtocolTest {
    @Test
    fun timeSyncPacketUsesObservedA3Layout() {
        val calendar = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 20, 19, 5, 46)
        }
        assertArrayEquals(
            byteArrayOf(
                0xA3.toByte(), 0x07, 0xEA.toByte(), 0x09, 0x14, 0x13, 0x05, 0x2E,
            ),
            FastrackProtocol.buildTimeSyncPacket(calendar),
        )
    }

    @Test
    fun dynamicStartPacketsUseVerifiedD602ThenE511Sequence() {
        assertArrayEquals(
            byteArrayOf(0xD6.toByte(), 0x02),
            FastrackProtocol.buildDynamicHeartRateModePacket(),
        )
        assertArrayEquals(
            byteArrayOf(0xE5.toByte(), 0x11),
            FastrackProtocol.buildLiveHeartRateStartPacket(),
        )
    }

    @Test
    fun liveHeartRateFrameUsesObservedE51100Layout() {
        assertEquals(
            104,
            FastrackProtocol.decodeLiveHeartRate(
                byteArrayOf(0xE5.toByte(), 0x11, 0x00, 0x68),
            ),
        )
    }

    @Test
    fun malformedHeartRateFrameIsIgnored() {
        assertEquals(
            null,
            FastrackProtocol.decodeLiveHeartRate(
                byteArrayOf(0xE5.toByte(), 0x11, 0x00, 0x05),
            ),
        )
    }
}
