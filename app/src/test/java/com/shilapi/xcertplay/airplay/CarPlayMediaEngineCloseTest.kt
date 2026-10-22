package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.junit.Assert.*
import org.junit.Test

class CarPlayMediaEngineCloseTest {
    @Test fun closeReleasesStreamsOnceAndRejectsEveryNewSetup() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events.add(type to active) }
        })
        val session = session()
        var closures = 0
        val field = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val streams = field.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable { closures++ }
        try {
            engine.close(); engine.close()
            assertEquals(1, closures)
            assertEquals(listOf(110 to false), events)
            assertTrue(streams.isEmpty())
            assertNull(engine.onScreen(session, 110, emptyMap()))
            assertNull(engine.onAudio(session, 100, emptyMap()))
            assertNull(engine.onDataStream(session, emptyMap()))
            assertNull(engine.onBufferedAudio(session, emptyMap()))
            val lazy = CarPlayMediaEngine::class.java.getDeclaredField("microphoneSchedulerLazy").apply { isAccessible = true }.get(engine) as Lazy<*>
            assertFalse("closing unused engine created a microphone scheduler", lazy.isInitialized())
        } finally { session.close() }
    }

    @Test fun closeShutsDownAlreadyInitializedMicrophoneScheduler() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val getter = CarPlayMediaEngine::class.java.getDeclaredMethod("getMicrophoneScheduler").apply { isAccessible = true }
        val scheduler = getter.invoke(engine) as ScheduledThreadPoolExecutor
        engine.close()
        assertTrue(scheduler.isShutdown)
        engine.close()
        assertTrue(scheduler.isShutdown)
    }

    private fun session() = AirPlaySession(Socket(), AirPlayConfig("Test", "00:11:22:33:44:55", "", "1",
        AirPlayDisplayConfig(800, 480)), AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
}
