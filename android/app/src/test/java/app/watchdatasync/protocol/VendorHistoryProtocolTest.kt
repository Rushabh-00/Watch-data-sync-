package app.watchdatasync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VendorHistoryProtocolTest {
    @Test
    fun ecDateMarkerMatchesObservedCompleteSyncPacket() {
        val packet = VendorHistoryProtocol.decode("EC 01 07 EA 09 0A 13")

        val decoded = packet as VendorHistoryProtocol.EcDateMarker
        assertEquals(2026, decoded.year)
        assertEquals(9, decoded.month)
        assertEquals(10, decoded.day)
        assertEquals(0x13, decoded.marker)
    }

    @Test
    fun ecBatchUsesSixByteRecordFraming() {
        val packet = VendorHistoryProtocol.decode(
            "EC 02 04 10 02 00 00 01 04 11 02 00 00 1F 04 30 04 00 00 27",
        )

        val decoded = packet as VendorHistoryProtocol.EcBatch
        assertEquals(3, decoded.records.size)

        assertEquals(4, decoded.records[0].hour)
        assertEquals(16, decoded.records[0].minute)
        assertEquals(2, decoded.records[0].type)
        assertEquals(1, decoded.records[0].value)

        assertEquals(4, decoded.records[2].hour)
        assertEquals(48, decoded.records[2].minute)
        assertEquals(4, decoded.records[2].type)
        assertEquals(0x27, decoded.records[2].value)
    }

    @Test
    fun ecBatchAcceptsTheCapturedFinalTypeMarker() {
        val packet = VendorHistoryProtocol.decode(
            "EC 02 09 26 FF 00 00 00",
        )

        val decoded = packet as VendorHistoryProtocol.EcBatch
        assertEquals(1, decoded.records.size)
        assertEquals(0xFF, decoded.records.single().type)
        assertEquals(0, decoded.records.single().value)
    }

    @Test
    fun fortyFourFaPageParsesTwelveThreeByteSamples() {
        val packet = VendorHistoryProtocol.decode(
            "44 FA 07 EA 09 13 04 00 " +
                "02 23 0E 01 21 13 01 2B 0E 01 26 0C 02 24 12 02 " +
                "26 0A 02 2E 0C 02 27 12 02 20 0D 01 2E 0B 00 37 " +
                "13 01 28 10",
        )

        val decoded = packet as VendorHistoryProtocol.FaPage
        assertEquals(2026, decoded.year)
        assertEquals(9, decoded.month)
        assertEquals(19, decoded.day)
        assertEquals(4, decoded.hour)
        assertEquals(0, decoded.minute)
        assertEquals(12, decoded.samples.size)
        assertEquals(0x02, decoded.samples.first().valueA)
        assertEquals(0x23, decoded.samples.first().valueB)
        assertEquals(0x0E, decoded.samples.first().flags)
    }

    @Test
    fun fortyFourFaCompletionAndFinalTransferMarkersArePreserved() {
        val fa = VendorHistoryProtocol.decode("44 FA FD 0C")
        val transfer = VendorHistoryProtocol.decode("52 00 FD")

        assertNotNull(fa)
        assertEquals(0x0C, (fa as VendorHistoryProtocol.FaCompletion).marker)
        assertNotNull(transfer)
        assertEquals(
            "FT_38093 vendor transfer completion • 52 00 FD",
            VendorHistoryProtocol.describe(transfer!!),
        )
    }
}
