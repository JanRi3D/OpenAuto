package me.ri3d.openauto.carplay.media

object AudioFrames {
    private val aacRates = intArrayOf(96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350)
    fun aacConfig(rate: Int, channels: Int): ByteArray {
        val index = aacRates.indexOf(rate)
        require(index >= 0 && channels in 1..2) { "unsupported AAC format" }
        val bits = (2 shl 11) or (index shl 7) or (channels shl 3)
        return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
    }

    fun pcmLittleEndian(bytes: ByteArray, channels: Int): ByteArray {
        require(channels in 1..2 && bytes.size % (channels * 2) == 0) { "partial PCM sample" }
        val out = bytes.copyOf()
        for (i in out.indices step 2) { out[i] = bytes[i+1]; out[i+1] = bytes[i] }
        return out
    }

    /** The payload type is not checked: the format comes from SETUP, as in DiPlay, whatever type the phone writes. */
    fun rtpPayload(bytes: ByteArray): ByteArray {
        require(bytes.size in 12..65535 && (bytes[0].toInt() and 0xc0) == 0x80) { "invalid RTP header" }
        var start = 12 + (bytes[0].toInt() and 15) * 4
        require(start <= bytes.size) { "truncated RTP CSRC" }
        if (bytes[0].toInt() and 16 != 0) {
            require(start + 4 <= bytes.size) { "truncated RTP extension" }
            val words = ((bytes[start+2].toInt() and 255) shl 8) or (bytes[start+3].toInt() and 255)
            start += 4 + words * 4
        }
        var end = bytes.size
        if (bytes[0].toInt() and 32 != 0) {
            val padding = bytes.last().toInt() and 255
            require(padding > 0 && padding <= end - start) { "invalid RTP padding" }
            end -= padding
        }
        require(start < end) { "empty or truncated RTP body" }
        return bytes.copyOfRange(start,end)
    }
}

/** Extend the unsigned RTP sample counter, allowing timestamped reordering across wrap. */
class RtpSampleClock(private val rate: Int) {
    private var last: Int? = null
    private var samples = 0L
    init { require(rate > 0) }
    fun micros(sample: Int): Long {
        val previous = last
        if (previous == null) { last = sample; return 0 }
        samples += (sample - previous).toLong()
        last = sample
        return samples * 1_000_000L / rate
    }
}
