package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test fun twoByteAvccNalLengthsAreExplicitlyDeclinedBeforeConfigDelivery() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val configured = java.util.concurrent.atomic.AtomicBoolean()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val stream = ScreenStream(ByteArray(32), bindAddress = java.net.InetAddress.getByName("127.0.0.1"))
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onConfig(codecData: ByteArray) { configured.set(true) }
            override fun onClosed(cause: Throwable?) { failure.set(cause); closed.countDown() }
        })
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                val avcc = byteArrayOf(1, 0x42, 0, 0x1e, 0xfd.toByte(), 0xe1.toByte(), 0, 2, 0x67, 0x42, 1, 0, 2, 0x68, 0x0e)
                val header = ByteArray(128)
                header[0] = avcc.size.toByte(); header[4] = 1
                socket.getOutputStream().write(header + avcc); socket.getOutputStream().flush()
                org.junit.Assert.assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                org.junit.Assert.assertFalse(configured.get())
                org.junit.Assert.assertTrue(failure.get() is IllegalArgumentException)
            }
        } finally { stream.close() }
    }
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test fun readsTheFrameTimeFromTheScreenHeader() {
        val header = ByteArray(128)
        // NTP 32.32 little-endian at byte 8: 3 s + 0x40000000/2^32 s = 3.25 s.
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(3_250_000_000L, ScreenCodec.senderNanos(header))
        // One 1/60 s step, as the iPhone stamps consecutive main-screen frames.
        val next = raw + 0x0444_4444L
        for (i in 0 until 8) header[8 + i] = (next ushr (8 * i)).toByte()
        assertEquals(16_666_666L, ScreenCodec.senderNanos(header) - 3_250_000_000L)
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(12)))
    }
}
