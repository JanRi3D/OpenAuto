package me.ri3d.openauto.carplay.media

import java.io.ByteArrayOutputStream

/** Bounded ISO/IEC 14496-15 AVC configuration and length-prefixed access-unit conversion. */
object AvcFraming {
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    data class Configuration(val lengthBytes: Int, val annexB: ByteArray)

    /** MediaSink receives Annex B from ScreenStream, never length-prefixed wire payloads. */
    fun sinkAnnexB(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES) { "invalid AVC access-unit size" }
        fun startCode(at: Int): Int {
            if (at+3 > bytes.size || bytes[at]!=0.toByte() || bytes[at+1]!=0.toByte())return 0
            if (at+4<=bytes.size && bytes[at+2]==0.toByte() && bytes[at+3]==1.toByte())return 4
            return if(bytes[at+2]==1.toByte())3 else 0
        }
        var cursor=0
        while(cursor<bytes.size) {
            val prefix=startCode(cursor)
            require(prefix>0) { "MediaSink AVC frame must be Annex B" }
            val header=cursor+prefix
            require(header<bytes.size && startCode(header)==0) { "empty AVC NAL" }
            val nal=bytes[header].toInt() and 255
            require(nal and 128 == 0 && nal and 31 in 1..23) { "invalid AVC NAL header" }
            cursor=header+1
            while(cursor<bytes.size && startCode(cursor)==0)cursor++
        }
        return bytes
    }

    fun parseConfiguration(bytes: ByteArray): Configuration {
        require(bytes.size in 7..65536 && bytes[0].toInt() == 1) { "invalid avcC" }
        val lengthBytes = (bytes[4].toInt() and 3) + 1
        require(lengthBytes != 3) { "reserved AVC NAL length size" }
        val out = ByteArrayOutputStream()
        var cursor = 6
        fun parameterSet() {
            require(cursor + 2 <= bytes.size) { "truncated avcC length" }
            val size = ((bytes[cursor].toInt() and 255) shl 8) or (bytes[cursor + 1].toInt() and 255)
            cursor += 2
            require(size > 0 && size <= bytes.size - cursor) { "truncated avcC parameter set" }
            out.write(byteArrayOf(0,0,0,1))
            out.write(bytes, cursor, size)
            cursor += size
        }
        val spsCount = bytes[5].toInt() and 31
        require(spsCount > 0) { "avcC has no SPS" }
        repeat(spsCount) { parameterSet() }
        require(cursor < bytes.size) { "avcC has no PPS count" }
        val ppsCount = bytes[cursor++].toInt() and 255
        require(ppsCount > 0) { "avcC has no PPS" }
        repeat(ppsCount) { parameterSet() }
        return Configuration(lengthBytes, out.toByteArray())
    }

    fun toAnnexB(bytes: ByteArray, lengthBytes: Int): ByteArray {
        require(lengthBytes == 1 || lengthBytes == 2 || lengthBytes == 4)
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES)
        var cursor = 0
        var total = 0L
        while (cursor < bytes.size) {
            require(bytes.size - cursor >= lengthBytes) { "truncated NAL length" }
            var length = 0L
            repeat(lengthBytes) { length = (length shl 8) or (bytes[cursor++].toLong() and 255) }
            require(length > 0 && length <= bytes.size - cursor) { "invalid NAL length" }
            total += 4 + length
            require(total <= MAX_FRAME_BYTES) { "AVC frame too large" }
            cursor += length.toInt()
        }
        val out = ByteArray(total.toInt())
        cursor = 0
        var target = 0
        while (cursor < bytes.size) {
            var length = 0
            repeat(lengthBytes) { length = (length shl 8) or (bytes[cursor++].toInt() and 255) }
            out[target + 3] = 1
            target += 4
            System.arraycopy(bytes, cursor, out, target, length)
            target += length
            cursor += length
        }
        return out
    }
}
