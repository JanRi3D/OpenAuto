package me.ri3d.openauto.carplay.media

import org.junit.Assert.*
import org.junit.Test
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder

class SoftwareOpusTest {
    @Test fun speechEncoderProducesDecodableTwentyMillisecondFrames() {
        val encoder = OpusEncoder(48000,1,OpusApplication.OPUS_APPLICATION_VOIP)
        encoder.setBitrate(32000)
        encoder.setComplexity(5)
        val decoder = OpusDecoder(48000,1)
        val encoded = ByteArray(1275)
        val decoded = ShortArray(960)
        var energy = 0L
        repeat(6) { frame ->
            val pcm = ShortArray(960) { index ->
                (Math.sin((frame * 960 + index) * 2.0 * Math.PI * 440.0 / 48000) * 8000).toInt().toShort()
            }
            val length = encoder.encode(pcm,0,960,encoded,0,encoded.size)
            assertTrue(length in 1..1275)
            assertEquals(960,decoder.decode(encoded,0,length,decoded,0,960,false))
            for (sample in decoded) energy += sample.toLong() * sample
        }
        assertTrue("encoder/decoder returned only silence", energy > 1_000_000)
    }
}
