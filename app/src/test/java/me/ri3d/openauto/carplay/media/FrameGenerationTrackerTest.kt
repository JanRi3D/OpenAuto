package me.ri3d.openauto.carplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FrameGenerationTrackerTest {
    @Test fun eachScreenGenerationNeedsItsOwnNewFrameAndNotifiesExactlyOnce() {
        val frames=FrameGenerationTracker()
        assertFalse(frames.consume(15)) // old output before any screen starts
        frames.begin(15)
        assertFalse(frames.consume(15))
        assertTrue(frames.consume(16))
        assertFalse(frames.consume(17))
        frames.end()
        assertFalse(frames.consume(18)) // old output while screen is closed
        frames.begin(18) // baseline captured after the preceding decoder has closed
        assertFalse(frames.consume(17)) // stale sample cannot notify the successor
        assertFalse(frames.consume(18))
        assertTrue(frames.consume(19))
        assertFalse(frames.consume(20))
        frames.end()
        assertFalse(frames.consume(21))
    }

    @Test fun concurrentObserversConsumeOneNotificationOnly() {
        val frames=FrameGenerationTracker()
        frames.begin(100)
        val start=CountDownLatch(1)
        val done=CountDownLatch(12)
        val notifications=AtomicInteger()
        repeat(12) {
            Thread {
                start.await()
                if(frames.consume(101))notifications.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1,notifications.get())
    }
}
