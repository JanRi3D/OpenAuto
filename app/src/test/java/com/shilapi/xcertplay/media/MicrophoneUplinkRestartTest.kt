package com.shilapi.xcertplay.media

import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/** The first read after a radio enters call mode can fail; capture must go on for the rest of the call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneUplinkRestartTest {
    @After fun reset() { ShadowAudioRecord.clearSource() }

    @Test(timeout = 10_000) fun aFailedFirstReadRestartsCaptureInsteadOfEndingIt() {
        val reads = AtomicInteger()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, isBlocking: Boolean): Int {
                if (reads.incrementAndGet() == 1) return AudioRecord.ERROR_INVALID_OPERATION
                Thread.sleep(5)
                return sizeInBytes
            }
        })
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3000
            val uplink = MicrophoneUplink(MicrophoneConfig("speechrecognition", 16000, 1, 103, 20,
                receiver.localAddress, receiver.localPort, ByteArray(32)))
            try {
                uplink.start()
                // Without a restart capture ends at the failed read and nothing arrives: SocketTimeoutException.
                receiver.receive(DatagramPacket(ByteArray(2048), 2048))
            } finally { uplink.close() }
        }
        assertTrue(reads.get() > 1)
    }
}
