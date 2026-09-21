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
    fun batteryRequestUsesVerifiedA2Opcode() {
        assertArrayEquals(
            byteArrayOf(0xA2.toByte()),
            FastrackProtocol.buildBatteryRequestPacket(),
        )
        assertEquals(
            WatchBattery(32, false),
            FastrackProtocol.decodeBattery(byteArrayOf(0xA2.toByte(), 32, 0x00)),
        )
        assertEquals(
            WatchBattery(95, true),
            FastrackProtocol.decodeBattery(byteArrayOf(0xA2.toByte(), 95, 0x01)),
        )
    }

    @Test
    fun invalidBatteryFrameIsIgnored() {
        assertEquals(
            null,
            FastrackProtocol.decodeBattery(byteArrayOf(0xA2.toByte(), 101)),
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
    @Test
    fun rssiQualityBandsAreStable() {
        assertEquals("Signal —", signalQualityForRssi(null))
        assertEquals("Excellent", signalQualityForRssi(-55))
        assertEquals("Good", signalQualityForRssi(-67))
        assertEquals("Fair", signalQualityForRssi(-80))
        assertEquals("Weak", signalQualityForRssi(-81))
    }

}
