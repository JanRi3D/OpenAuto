package me.ri3d.openauto.aa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule a real head unit broke: a phone sends one keyframe per stream, so the decoder must see
 * every frame after it or none until the next keyframe, and the phone is acknowledged once per frame.
 */
public class VideoQueueTest {
    private static final byte[] CONFIG = hex("00000001" + "6742801fda0320f69a8080808368509a80" + "00000001" + "68ce06e2");
    private static final byte[] IDR = hex("0000000165888400");
    private static final byte[] P = hex("00000001419a0000");

    private static final class Counter implements Media.VideoOut.Feedback {
        int acks, keyframeRequests;

        @Override
        public void consumed() {
            acks++;
        }

        @Override
        public void needKeyframe() {
            keyframeRequests++;
        }
    }

    private static VideoQueue queue(Counter c, int capacity) {
        VideoQueue q = new VideoQueue(capacity);
        q.setFeedback(c);
        return q;
    }

    private static void offer(VideoQueue q, byte[] unit) {
        q.offer(unit, 0, unit.length, 0);
    }

    @Test
    public void acknowledgesWhenTheDecoderTakesAUnitNotWhenItArrives() throws Exception {
        Counter c = new Counter();
        VideoQueue q = queue(c, 6);
        q.setOpen(true);
        offer(q, CONFIG);
        offer(q, IDR);
        offer(q, P);
        assertEquals("nothing consumed yet", 0, c.acks);
        VideoQueue.Unit u = q.take(0);
        assertTrue(u.config);
        q.done(u);
        assertEquals(1, c.acks);
        q.done(q.take(0));
        q.done(q.take(0));
        assertEquals(3, c.acks);
        assertNull(q.take(0));
        assertEquals(0, c.keyframeRequests);
    }

    @Test
    public void parameterSetsAndKeyframeInOneMessageAreAcknowledgedOnce() throws Exception {
        Counter c = new Counter();
        VideoQueue q = queue(c, 6);
        q.setOpen(true);
        byte[] both = new byte[CONFIG.length + IDR.length];
        System.arraycopy(CONFIG, 0, both, 0, CONFIG.length);
        System.arraycopy(IDR, 0, both, CONFIG.length, IDR.length);
        offer(q, both);
        VideoQueue.Unit config = q.take(0);
        assertTrue(config.config);
        assertEquals(CONFIG.length, config.len);
        q.done(config);
        assertEquals(0, c.acks);
        VideoQueue.Unit frame = q.take(0);
        assertFalse(frame.config);
        q.done(frame);
        assertEquals(1, c.acks);
    }

    /** "Sound but no picture": the stream began before the decoder existed and its only keyframe went by. */
    @Test
    public void aDecoderThatStartsLateAsksForAKeyframeAndSkipsUntilItComes() throws Exception {
        Counter c = new Counter();
        VideoQueue q = queue(c, 6);
        offer(q, CONFIG); // no decoder yet: acknowledged and remembered, not queued
        offer(q, IDR);
        offer(q, P);
        assertEquals(3, c.acks);
        assertNotNull(q.lastConfig());

        q.setOpen(true);
        offer(q, P); // cannot be decoded without the keyframe
        offer(q, P);
        assertNull(q.take(0));
        assertEquals(5, c.acks);
        assertEquals("one request, not one per frame", 1, c.keyframeRequests);

        offer(q, CONFIG); // the phone restarts the stream
        offer(q, IDR);
        offer(q, P);
        assertTrue(q.take(0).config);
        assertEquals(IDR.length, q.take(0).len);
        assertNotNull(q.take(0));
    }

    /** "Many artifacts": the decoder fell behind and single frames were dropped from the middle of the stream. */
    @Test
    public void overflowGivesUpEverythingUntilTheNextKeyframeInsteadOfDroppingOneFrame() throws Exception {
        Counter c = new Counter();
        VideoQueue q = queue(c, 3);
        q.setOpen(true);
        offer(q, IDR);
        offer(q, P);
        offer(q, P);
        offer(q, P); // no room
        assertNull("nothing with a hole in it is left to decode", q.take(0));
        assertEquals("every frame is acknowledged exactly once", 4, c.acks);
        assertEquals(1, c.keyframeRequests);
        offer(q, P); // still no keyframe: skipped
        assertNull(q.take(0));
        offer(q, IDR);
        assertNotNull(q.take(0));
        assertEquals(5, c.acks);
    }

    @Test
    public void stoppingTheDecoderAcknowledgesWhatWasQueued() {
        Counter c = new Counter();
        VideoQueue q = queue(c, 6);
        q.setOpen(true);
        offer(q, IDR);
        offer(q, P);
        q.setOpen(false);
        assertEquals(2, c.acks);
        assertEquals(0, q.size());
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
