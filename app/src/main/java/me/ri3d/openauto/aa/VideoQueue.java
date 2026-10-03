package me.ri3d.openauto.aa;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Coded video between the protocol reader thread and the decoder thread.
 *
 * A real phone sends one keyframe when the stream starts and only predicted frames after it
 * (observed: 1 IDR, then 2320 P frames). A coded frame may therefore never be left out: every later
 * picture would be built on the missing one. An earlier version dropped the oldest unit when the
 * decoder fell behind, which a real head unit showed as a screen full of artifacts, and lost the
 * only keyframe when the stream started before the decoder did (sound but no picture).
 *
 * This queue never drops a single frame:
 * <ul>
 * <li>Each unit is acknowledged when the decoder has taken it, not when it arrives. The phone sends
 *     at most {@code max_unacked} frames ahead, so a slow decoder slows the phone down.</li>
 * <li>If the queue overflows anyway, or decoding starts in the middle of a stream, everything up to
 *     the next keyframe is discarded (and acknowledged) and the phone is asked for a keyframe.</li>
 * </ul>
 */
public final class VideoQueue {
    /** One access unit, or the parameter sets (SPS/PPS) that precede a keyframe. */
    public static final class Unit {
        public byte[] data = new byte[64 * 1024];
        public int len;
        public long pts;
        public boolean config;
        boolean ack;
    }

    private static final long REQUEST_INTERVAL_MS = 2000;
    private static final int MAX_UNANSWERED = 5;

    private final ArrayBlockingQueue<Unit> queue, pool;
    private volatile Media.VideoOut.Feedback feedback;
    private volatile boolean open;
    private volatile boolean awaitingKeyframe = true;
    private volatile byte[] lastConfig;
    private long lastRequestMs;
    private int unanswered;

    public volatile long unitsIn, bytesIn, unitsQueued, unitsDiscarded, keyframeRequests;

    public VideoQueue(int capacity) {
        queue = new ArrayBlockingQueue<>(capacity);
        pool = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) pool.offer(new Unit());
    }

    public void setFeedback(Media.VideoOut.Feedback f) {
        feedback = f;
    }

    /**
     * A decoder starts (true) or stops (false) taking units. A decoder that starts has seen nothing,
     * so it always begins at a keyframe.
     */
    public void setOpen(boolean on) {
        open = on;
        awaitingKeyframe = true;
        unanswered = 0;
        if (!on) discardQueued();
    }

    /** Reader thread: one message from the phone. Always results in exactly one acknowledgement. */
    public void offer(byte[] data, int off, int len, long pts) {
        unitsIn++;
        bytesIn += len;
        int split = parameterSetEnd(data, off, len);
        if (split > 0) lastConfig = Arrays.copyOfRange(data, off, off + split);
        if (!open) { // nothing can decode it; the stream is restarted from a keyframe when a decoder appears
            unitsDiscarded++;
            acknowledge();
            return;
        }
        boolean frame = split < len;
        if (frame && awaitingKeyframe) {
            if (H264.isKeyframe(data, off + split, len - split)) {
                awaitingKeyframe = false;
                unanswered = 0;
            } else {
                requestKeyframe(); // may give up waiting
                if (awaitingKeyframe) { // this frame cannot be decoded
                    unitsDiscarded++;
                    frame = false;
                }
            }
        }
        if (split == 0 && !frame) {
            acknowledge();
            return;
        }
        if (split > 0 && !put(data, off, split, pts, true, !frame)) return;
        if (frame) put(data, off + split, len - split, pts, false, true);
    }

    private boolean put(byte[] data, int off, int len, long pts, boolean config, boolean ack) {
        Unit u = pool.poll();
        if (u == null) {
            // The decoder is not keeping up and the phone did not wait for acknowledgements. Start over
            // from a fresh keyframe instead of decoding with a hole in the stream.
            unitsDiscarded++;
            discardQueued();
            awaitingKeyframe = true;
            requestKeyframe();
            acknowledge();
            return false;
        }
        if (u.data.length < len) u.data = new byte[len + len / 4];
        System.arraycopy(data, off, u.data, 0, len);
        u.len = len;
        u.pts = pts;
        u.config = config;
        u.ack = ack;
        queue.offer(u); // cannot fail: a pooled unit was free, so the queue has room
        unitsQueued++;
        return true;
    }

    private void discardQueued() {
        Unit u;
        while ((u = queue.poll()) != null) {
            unitsDiscarded++;
            if (u.ack) acknowledge();
            pool.offer(u);
        }
    }

    private void acknowledge() {
        Media.VideoOut.Feedback f = feedback;
        if (f != null) f.consumed();
    }

    /** At most one request per two seconds; after a few unanswered ones the stream is decoded as it is. */
    private void requestKeyframe() {
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REQUEST_INTERVAL_MS) return;
        if (unanswered >= MAX_UNANSWERED) {
            awaitingKeyframe = false; // this phone does not answer the request; better a damaged picture than none
            return;
        }
        lastRequestMs = now;
        unanswered++;
        keyframeRequests++;
        Media.VideoOut.Feedback f = feedback;
        if (f != null) f.needKeyframe();
    }

    /** Decoder thread: the next unit, or null after the timeout. Give it back with {@link #done}. */
    public Unit take(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Decoder thread: the unit is in the codec. Acknowledges it to the phone and recycles the buffer. */
    public void done(Unit u) {
        if (u.ack) acknowledge();
        pool.offer(u);
    }

    /** The parameter sets as last received, for a decoder that starts after they went by; may be null. */
    public byte[] lastConfig() {
        return lastConfig;
    }

    public int size() {
        return queue.size();
    }

    /**
     * If the unit starts with SPS/PPS NALs, returns the offset where the first slice NAL begins
     * (or len when the unit holds parameter sets only); 0 when it does not start with SPS.
     */
    static int parameterSetEnd(byte[] d, int off, int len) {
        int end = off + len;
        int p = H264.nalStart(d, off, end);
        if (p < 0 || (d[p] & 0x1F) != 7) return 0;
        while (true) {
            int next = H264.nalStart(d, p + 1, end);
            if (next < 0) return len;
            int type = d[next] & 0x1F;
            if (type != 7 && type != 8 && type != 6) { // first non-parameter NAL (slice/IDR)
                int startCode = next - 3;
                if (startCode > off && d[startCode - 1] == 0) startCode--; // 4-byte start code
                return startCode - off;
            }
            p = next;
        }
    }
}
