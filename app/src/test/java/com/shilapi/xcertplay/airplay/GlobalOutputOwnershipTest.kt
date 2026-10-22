package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class GlobalOutputOwnershipTest {
    @Test fun newerScreenOwnsOutputAndRetiredSessionCannotDeliverStopOrReceiveInput() {
        val events = CopyOnWriteArrayList<String>()
        val diagnostics = CopyOnWriteArrayList<(String) -> Unit>()
        val rendered = CopyOnWriteArrayList<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events.add("active:$active") }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events.add("frame:${naluBytes[0]}") }
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics.add(handler) }
        })
        val a = session(engine, object : AirPlaySessionListener { override fun onVideoFrameRendered(session: AirPlaySession) { rendered.add("A") } })
        val b = session(engine, object : AirPlaySessionListener { override fun onVideoFrameRendered(session: AirPlaySession) { rendered.add("B") } })
        try {
            assertNotNull(engine.onScreen(a, 110, setup()))
            assertNotNull(a.mainScreenSessionToken())
            val oldDiagnostic = diagnostics.last()
            val old = screenListener(engine, a)
            old.onFrame(byteArrayOf(1))
            assertNotNull(engine.onScreen(b, 110, setup()))
            val current = screenListener(engine, b)
            val currentDiagnostic = diagnostics.last()
            assertNull("retired main screen retained input eligibility", a.mainScreenSessionToken())
            events.clear()
            a.close()
            old.onFrame(byteArrayOf(2)); current.onFrame(byteArrayOf(3))
            oldDiagnostic("first frame rendered"); currentDiagnostic("first frame rendered")
            assertEquals(listOf("frame:3"), events.toList())
            assertEquals(listOf("B"), rendered.toList())
        } finally { a.close(); b.close(); engine.close() }
    }

    @Test fun newerAudioOwnsSinkAndRetiredSessionCannotStopIt() {
        val events = CopyOnWriteArrayList<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop") }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events.add("rtp:${rtp.last()}") }
        })
        val a = session(engine); val b = session(engine)
        try {
            val oldPort = (engine.onAudio(a, 100, setup())!!["dataPort"] as Number).toInt()
            sendAudio(oldPort, 1)
            waitFor { events.contains("rtp:1") }
            val currentPort = (engine.onAudio(b, 100, setup())!!["dataPort"] as Number).toInt()
            events.clear()
            a.close()
            sendAudio(oldPort, 2); sendAudio(currentPort, 3)
            waitFor { events.contains("rtp:3") }
            assertEquals(listOf("rtp:3"), events.toList())
        } finally { a.close(); b.close(); engine.close() }
    }

    @Test fun advertisedCompatibilityAudioTypeIsAccepted() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val a = session(engine)
        try { assertNotNull(engine.onAudio(a, 100, setup() + ("audioType" to "compatibility"))) }
        finally { a.close(); engine.close() }
    }

    private fun setup(): Map<String, Any?> = mapOf("streamConnectionID" to 42L, "audioType" to "media", "audioFormat" to 0x8000L)
    private fun session(engine: CarPlayMediaEngine, listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession = AirPlaySession(Socket(),
        AirPlayConfig("Test", "00:11:22:33:44:55", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, listener, engine).also {
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(it.pairVerify, ByteArray(32))
    }
    @Suppress("UNCHECKED_CAST")
    private fun screenListener(engine: CarPlayMediaEngine, session: AirPlaySession): ScreenStream.Listener {
        val streams = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(engine)
            as Map<CarPlayMediaEngine.StreamKey, Closeable>
        return ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(streams[CarPlayMediaEngine.StreamKey(session, 110)]) as ScreenStream.Listener
    }
    private fun sendAudio(port: Int, payload: Int) {
        val key = AirPlayCrypto.hkdfSha512(ByteArray(32), "DataStream-Salt42".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray())
        val bytes = MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(), byteArrayOf(payload.toByte()), 1)
        DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port)) }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
