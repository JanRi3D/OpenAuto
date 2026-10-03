package me.ri3d.openauto.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

import me.ri3d.openauto.aa.H264;
import me.ri3d.openauto.aa.Media;
import me.ri3d.openauto.aa.VideoQueue;
import me.ri3d.openauto.diag.Decoders;

/**
 * H.264 to a Surface through the API 16 MediaCodec path (getInputBuffers / dequeue loops). The
 * reader thread hands access units to a {@link VideoQueue}; the decoder thread feeds the codec and
 * renders every output buffer as soon as it is ready (lowest latency on old devices). Parameter sets
 * (SPS/PPS) are submitted with BUFFER_FLAG_CODEC_CONFIG for decoders that need them before the first
 * frame. No coded frame is ever dropped; see VideoQueue for how a slow decoder is handled.
 */
public final class VideoDecoder implements Media.VideoOut {
    private static final String TAG = "VideoDecoder";

    private final Object lock = new Object();
    private final VideoQueue queue = new VideoQueue(6);
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    /** How the video margins are removed when the Surface has the shape of the content area. */
    public static final int CROP_NONE = 0;   // not here: the view shows the whole frame or crops it itself
    public static final int CROP_SCALE = 1;  // the codec's scaling mode: applied by a hardware composer
    public static final int CROP_STREAM = 2; // cropping fields written into the stream's SPS, plus the scaling mode

    private Surface surface;
    private volatile int crop;
    private volatile int marginWidth, marginHeight;
    private int width, height;
    private boolean opened;
    private MediaCodec codec;
    private ByteBuffer[] inputs;
    private Thread thread;
    private volatile boolean running;
    private volatile String error;
    private boolean configFed; // decoder thread only

    public volatile long framesRendered;
    public volatile long firstFrameAtMs, lastFrameAtMs;
    public volatile String outputFormat;
    public volatile String codecName;
    /** Settings › Video › Decoder: try the software decoder first (for hardware decoders that misbehave). */
    public volatile boolean preferSoftware;

    /**
     * The Surface to render on; null when it is gone. Called from the UI thread.
     *
     * @param crop one of CROP_NONE, CROP_SCALE, CROP_STREAM
     */
    public void setSurface(Surface s, int crop) {
        synchronized (lock) {
            if (surface == s && this.crop == crop) return;
            this.crop = crop;
            Log.i(TAG, "surface " + (s == null ? "removed" : "set") + (opened ? " (stream open)" : " (no stream yet)"));
            stopCodec();
            surface = s;
            if (opened && s != null) startCodec();
        }
    }

    /** The video margins of the current stream; only used when the decoder does the cropping. */
    public void setMargins(int marginWidth, int marginHeight) {
        this.marginWidth = marginWidth;
        this.marginHeight = marginHeight;
    }

    public boolean hasSurface() {
        synchronized (lock) {
            return surface != null;
        }
    }

    public String error() {
        return error;
    }

    @Override
    public boolean feedback(Feedback f) {
        queue.setFeedback(f);
        return true;
    }

    @Override
    public boolean open(int w, int h, int fps) {
        synchronized (lock) {
            width = w;
            height = h;
            opened = true;
            error = null;
            // Fail SETUP only when the device has no H.264 decoder at all; a missing Surface just waits.
            if (Decoders.avcDecoders().isEmpty()) {
                error = "no H.264 decoder on this device";
                Log.e(TAG, error);
                return false;
            }
            if (surface != null) startCodec(); else Log.i(TAG, "stream open, waiting for a Surface");
            return true;
        }
    }

    /**
     * Tries the decoders by name, hardware first. {@code createDecoderByType} takes whatever the device
     * lists first, and a real head unit lists the software decoder before its hardware one.
     */
    private void startCodec() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String n : Decoders.avcDecoders()) { // hardware first
            if (preferSoftware && Decoders.isSoftware(n)) names.add(0, n); else names.add(n);
        }
        String failures = "";
        for (String name : names) {
            MediaCodec c = null;
            try {
                c = MediaCodec.createByCodecName(name);
                c.configure(MediaFormat.createVideoFormat("video/avc", width, height), surface, null, 0);
                c.start();
                applyScaling(c);
                codec = c;
                codecName = name;
                break;
            } catch (Throwable t) {
                failures += name + ": " + t + "; ";
                Log.w(TAG, "decoder " + name + " failed to start: " + t);
                if (c != null) {
                    try { c.release(); } catch (Throwable ignored) { }
                }
            }
        }
        if (codec == null) {
            error = names.isEmpty() ? "no H.264 decoder on this device" : "no decoder could start (" + failures + ")";
            Log.e(TAG, error);
            return;
        }
        try {
            inputs = codec.getInputBuffers();
            configFed = false;
            running = true;
            queue.setOpen(true); // from here on units are queued, beginning with the next keyframe
            thread = new Thread(this::loop, "video-decoder");
            thread.start();
            Log.i(TAG, "decoder started " + width + "x" + height + " using " + codecName + (crop == CROP_SCALE ? ", cropping by scaling mode" : crop == CROP_STREAM ? ", cropping through the stream" : ""));
        } catch (Throwable t) {
            error = "decoder start failed: " + t;
            Log.e(TAG, error);
            running = false;
            queue.setOpen(false);
            releaseCodec();
        }
    }

    /**
     * Centre-crops the frame to the shape of the Surface, which has the shape of the content area.
     * Only a hardware composer applies this mode: where the system composites the layer with the GPU
     * (Android 4.1 emulator) the whole frame is stretched instead. Has to be repeated for new output
     * buffers.
     */
    private void applyScaling(MediaCodec c) {
        if (crop == CROP_NONE) return;
        try {
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        } catch (RuntimeException e) {
            Log.w(TAG, "scaling mode not accepted: " + e);
        }
    }

    private void stopCodec() {
        running = false;
        queue.setOpen(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        releaseCodec();
    }

    private void releaseCodec() {
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) { }
            try { codec.release(); } catch (Throwable ignored) { }
            codec = null;
            inputs = null;
        }
    }

    @Override
    public void write(byte[] data, int off, int len, long ptsUs) {
        queue.offer(data, off, len, ptsUs);
    }

    private void loop() {
        MediaCodec c = codec;
        try {
            while (running) {
                VideoQueue.Unit u = queue.take(20);
                if (u != null) {
                    if (u.config) {
                        feedConfig(c, u.data, u.len);
                    } else {
                        // A decoder started after the parameter sets went by gets the remembered ones first.
                        byte[] config = queue.lastConfig();
                        if (!configFed && config != null) feedConfig(c, config, config.length);
                        feed(c, u.data, u.len, u.pts, 0);
                    }
                    queue.done(u); // acknowledged to the phone only now: the decoder sets the pace
                }
                drain(c);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            error = "decoder failed: " + t;
            Log.e(TAG, error);
            running = false;
            queue.setOpen(false);
        }
    }

    /**
     * Parameter sets. With CROP_STREAM the video margins are written into the SPS as frame cropping,
     * the standard way to tell a decoder what part of the picture to show. Not the default: one
     * decoder (Android 16 emulator) applied the cropped size but not the top offset and showed a
     * shifted picture, and Android 4.1 shows a software decoder's output uncropped.
     */
    private void feedConfig(MediaCodec c, byte[] data, int len) throws InterruptedException {
        int mw = marginWidth, mh = marginHeight;
        if (crop == CROP_STREAM && (mw | mh) != 0) {
            byte[] cropped = H264.cropSps(data, 0, len, mw, mh);
            if (cropped != null) {
                data = cropped;
                len = cropped.length;
            }
            if (!configFed) Log.i(TAG, cropped != null ? "stream cropped by " + mw + "x" + mh + " through its SPS" : "SPS could not be rewritten, relying on the scaling mode");
        }
        feed(c, data, len, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        configFed = true;
    }

    private void feed(MediaCodec c, byte[] data, int len, long pts, int flags) throws InterruptedException {
        while (running) {
            int idx = c.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer in = inputs[idx];
                in.clear();
                in.put(data, 0, len);
                c.queueInputBuffer(idx, 0, len, pts, flags);
                return;
            }
            drain(c); // input starved because outputs are not consumed yet
        }
    }

    @SuppressWarnings("deprecation")
    private void drain(MediaCodec c) {
        while (running) {
            int out = c.dequeueOutputBuffer(info, 0);
            if (out >= 0) {
                c.releaseOutputBuffer(out, true);
                long now = System.currentTimeMillis();
                if (framesRendered == 0) firstFrameAtMs = now;
                lastFrameAtMs = now;
                framesRendered++;
                if (framesRendered % 300 == 0) Log.i(TAG, stats()); // about every 10 s at 30 fps
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = String.valueOf(c.getOutputFormat());
                Log.i(TAG, "output format " + outputFormat);
                applyScaling(c);
            } else if (out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                applyScaling(c); // the scaling mode is reset with new buffers; they are never touched directly
            } else {
                return;
            }
        }
    }

    /** One line of counters for logs and Diagnostics. */
    public String stats() {
        long span = Math.max(1, lastFrameAtMs - firstFrameAtMs);
        return String.format(java.util.Locale.US, "video: in %d units (%d KiB), queued %d, discarded %d, keyframe requests %d, rendered %d (%.1f fps avg), queue %d, %s",
                queue.unitsIn, queue.bytesIn / 1024, queue.unitsQueued, queue.unitsDiscarded, queue.keyframeRequests, framesRendered,
                framesRendered > 1 ? (framesRendered - 1) * 1000.0 / span : 0.0, queue.size(), codecName);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (opened && queue.unitsIn > 0) Log.i(TAG, stats());
            opened = false;
            stopCodec();
        }
    }
}
