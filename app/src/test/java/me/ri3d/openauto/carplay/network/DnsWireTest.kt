package me.ri3d.openauto.carplay.network

import org.junit.Assert.*
import org.junit.Test

class DnsWireTest {
    @Test fun addressResolutionUsesARecordRatherThanPtr() {
        val q=DnsWire.query("iphone.local.",1)
        assertEquals(1,q[q.size-3].toInt() and 255)
    }
    @Test fun advertisementHasServicePortTxtAndTheSelectedAddress() {
        val packet = DnsWire.advertisement("OpenAuto", "OpenAuto-test.local.", 7000,
            byteArrayOf(192.toByte(),168.toByte(),1,10), mapOf("pk" to "1234", "features" to "0x1"), 120)
        val message = DnsWire.read(packet)
        assertTrue(message.response)
        assertTrue(message.records.any { it.type == 12 && it.name == "_airplay._tcp.local." })
        val srv = message.records.first { it.type == 33 }
        assertEquals(7000, srv.port)
        assertEquals("OpenAuto-test.local.", srv.target)
        assertArrayEquals(byteArrayOf(192.toByte(),168.toByte(),1,10), message.records.first { it.type == 1 }.data)
    }

    @Test fun compressionPointerCycleIsRejected() {
        val packet = byteArrayOf(0,0,0,0,0,1,0,0,0,0,0,0,0xc0.toByte(),12,0,12,0,1)
        try { DnsWire.read(packet); fail("cyclic compression accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun excessiveRecordCountIsRejectedBeforeAllocation() {
        val packet = ByteArray(12)
        packet[6] = 0x7f; packet[7] = 0xff.toByte()
        try { DnsWire.read(packet); fail("unbounded record count accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun queryRoundTripsServiceName() {
        val m = DnsWire.read(DnsWire.query("_carplay-ctrl._tcp.local."))
        assertFalse(m.response)
        assertEquals(listOf("_carplay-ctrl._tcp.local."), m.questions)
    }
}
