package me.ri3d.openauto.carplay.media

import com.shilapi.xcertplay.airplay.ScreenCodec
import org.junit.Assert.*
import org.junit.Test

class ScreenMediaBoundaryTest {
    @Test fun actualScreenCodecOutputIsAcceptedWithoutASecondLengthConversion() {
        val wire=byteArrayOf(0,0,0,2,0x65,0x22,0,0,0,3,0x41,0x33,0x44)
        val screenOutput=ScreenCodec.lengthPrefixedToAnnexB(wire)
        val submitted=AvcFraming.sinkAnnexB(screenOutput)
        assertSame(screenOutput,submitted)
        assertArrayEquals(byteArrayOf(0,0,0,1,0x65,0x22,0,0,0,1,0x41,0x33,0x44),submitted)
    }

    @Test fun threeAndFourByteAnnexBStartCodesArePreserved() {
        val annexB=byteArrayOf(0,0,1,0x65,0x22,0,0,0,1,0x41,0x33,0x44)
        assertSame(annexB,AvcFraming.sinkAnnexB(annexB))
    }

    @Test fun sinkRejectsLengthsOrEmptyNalInsteadOfGuessingTheFormat() {
        rejects { AvcFraming.sinkAnnexB(byteArrayOf(0,0,0,2,0x65,0x22)) }
        rejects { AvcFraming.sinkAnnexB(byteArrayOf(0,0,0,1)) }
        rejects { AvcFraming.sinkAnnexB(byteArrayOf(0,0,1,0,0,1,0x65)) }
        rejects { AvcFraming.sinkAnnexB(byteArrayOf(0,0,1,0xe5.toByte(),1)) }
    }

    private fun rejects(block:()->Unit) {
        try {block();fail("malformed sink frame accepted")}catch(_:IllegalArgumentException){}
    }
}
