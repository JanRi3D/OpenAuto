package me.ri3d.openauto.carplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaFramingTest {
    @Test fun avccParsesEveryParameterSetAndNegotiatedLengthSize() {
        val config = byteArrayOf(1, 66, 0, 30, 0xfd.toByte(), 0xe2.toByte(),
            0, 2, 0x67, 1, 0, 2, 0x67, 2, 1, 0, 2, 0x68, 3)
        val parsed = AvcFraming.parseConfiguration(config)
        assertEquals(2, parsed.lengthBytes)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x67,1,0,0,0,1,0x67,2,0,0,0,1,0x68,3), parsed.annexB)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x65,5,0,0,0,1,0x41,6),
            AvcFraming.toAnnexB(byteArrayOf(0,2,0x65,5,0,2,0x41,6), 2))
    }

    @Test fun avccTruncatedAndOversizedNalAreRejected() {
        rejects { AvcFraming.parseConfiguration(byteArrayOf(1,66,0,30,0xff.toByte(),0xe1.toByte(),0,5,0x67)) }
        rejects { AvcFraming.toAnnexB(byteArrayOf(0x7f,0xff.toByte(),0xff.toByte(),0xff.toByte(),0x65), 4) }
        rejects { AvcFraming.toAnnexB(byteArrayOf(0,0), 2) }
    }

    @Test fun pcmSwapsEndianAndRejectsPartialSample() {
        assertArrayEquals(byteArrayOf(0x34,0x12,0xfe.toByte(),0xff.toByte()),
            AudioFrames.pcmLittleEndian(byteArrayOf(0x12,0x34,0xff.toByte(),0xfe.toByte()), 1))
        rejects { AudioFrames.pcmLittleEndian(byteArrayOf(1,2), 2) }
    }

    @Test fun aacConfigurationUsesNegotiatedRateAndChannels() {
        assertArrayEquals(byteArrayOf(0x12,0x10), AudioFrames.aacConfig(44100, 2))
        assertArrayEquals(byteArrayOf(0x11,0x88.toByte()), AudioFrames.aacConfig(48000, 1))
        rejects { AudioFrames.aacConfig(12345, 1) }
    }

    @Test fun rtpChecksHeaderExtensionsAndPaddingButNotPayloadType() {
        val packet = byteArrayOf(0x90.toByte(),96,0,1,0,0,0,4,0,0,0,0, 0,0,0,1, 1,2,3,4, 7,8)
        assertArrayEquals(byteArrayOf(7,8), AudioFrames.rtpPayload(packet))
        packet[1] = 0x80.toByte() // marker bit with payload type 0
        assertArrayEquals(byteArrayOf(7,8), AudioFrames.rtpPayload(packet))
        rejects { AudioFrames.rtpPayload(packet.copyOf(19)) }
        rejects { AudioFrames.rtpPayload(byteArrayOf(0x40,96,0,1,0,0,0,4,0,0,0,0, 7,8)) }
    }

    @Test fun sampleClockSurvivesWrapAndLatePackets() {
        val clock = RtpSampleClock(48000)
        assertEquals(0L, clock.micros(-480))
        assertEquals(20000L, clock.micros(480))
        assertEquals(10000L, clock.micros(0))
    }

    @Test fun touchKeepsIdsClampsEdgesAndReleasesMissingAndCancelledFingers() {
        val touch = TouchContacts()
        val first = touch.update(listOf(TouchContacts.Point(7,-5.0,250.0)),0.0,0.0,100.0,200.0)
        assertEquals(7, first.single().id)
        assertEquals(0.0, first.single().x,0.0)
        assertEquals(1.0, first.single().y,0.0)
        assertFalse(touch.update(emptyList(),0.0,0.0,100.0,200.0).single().down)
        touch.update(listOf(TouchContacts.Point(9,50.0,100.0)),0.0,0.0,100.0,200.0)
        assertFalse(touch.release().single().down)
        assertTrue(touch.release().isEmpty())
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("malformed media accepted") } catch (_: IllegalArgumentException) { }
    }
}
