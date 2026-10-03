package me.ri3d.openauto.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import me.ri3d.openauto.Perms;
import me.ri3d.openauto.aa.Media;

/**
 * Microphone capture for the phone's assistant: 16 kHz mono 16-bit PCM. Devices whose capture path
 * refuses 16 kHz (the API 16 emulator does) are opened at a supported rate and resampled linearly.
 */
public final class MicInput implements Media.MicIn {
    private static final String TAG = "MicInput";
    private static final int[] FALLBACK_RATES = {48000, 44100, 22050, 8000};

    private final Context ctx;
    /** The capture thread that may run; any other one winds down. It owns its AudioRecord from open to release. */
    private volatile Thread current;
    public volatile long bytesCaptured, opens;
    public volatile int captureRate;
    public volatile String error;
    /**
     * The microphone channel is always declared to the phone (a real phone drops a head unit that has
     * none); the Settings switch only decides whether an open request from the phone is honoured.
     */
    public volatile boolean enabled = true;

    public MicInput(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Returns at once: the caller is the protocol reader thread, and the platform's capture calls can
     * block for seconds (observed: startRecording never returned on an emulator, freezing the session).
     * True means capture was requested; a failure to capture shows up in {@link #error}.
     */
    @Override
    public boolean open(final int sampleRate, final Listener listener) {
        final Thread previous = current;
        current = null;
        if (!enabled) {
            error = "microphone is switched off in Settings › Audio";
            Log.i(TAG, error);
            return false;
        }
        if (!Perms.hasMic(ctx)) {
            error = "RECORD_AUDIO permission not granted";
            Log.w(TAG, error);
            return false;
        }
        error = null;
        Thread t = new Thread(() -> capture(sampleRate, listener, previous), "mic-capture");
        current = t;
        t.start();
        return true;
    }

    private void capture(int sampleRate, Listener listener, Thread previous) {
        Thread me = Thread.currentThread();
        if (previous != null) {
            try { previous.join(500); } catch (InterruptedException ignored) { } // let it release the device first
        }
        AudioRecord r = tryOpen(sampleRate);
        int inRate = sampleRate;
        for (int i = 0; r == null && i < FALLBACK_RATES.length; i++) {
            r = tryOpen(FALLBACK_RATES[i]);
            inRate = FALLBACK_RATES[i];
        }
        if (r == null) {
            error = "no usable capture rate (tried " + sampleRate + " and fallbacks)";
            Log.w(TAG, error);
            return;
        }
        try {
            r.startRecording();
            opens++;
            captureRate = inRate;
            Log.i(TAG, "capturing at " + inRate + " Hz" + (inRate == sampleRate ? "" : ", resampling to " + sampleRate));
            byte[] in = new byte[inRate * 2 / 16]; // 62.5 ms
            byte[] out = inRate == sampleRate ? in : new byte[sampleRate * 2 / 16 + 2];
            while (current == me) {
                int n = r.read(in, 0, in.length);
                if (n > 0) {
                    bytesCaptured += n;
                    if (inRate == sampleRate) {
                        listener.onMicData(in, n, System.currentTimeMillis() * 1000L);
                    } else {
                        int m = resample(in, n, inRate, out, sampleRate);
                        listener.onMicData(out, m, System.currentTimeMillis() * 1000L);
                    }
                } else if (n < 0) {
                    error = "AudioRecord read error " + n;
                    Log.w(TAG, error);
                    break;
                }
            }
        } catch (IllegalStateException e) {
            error = "startRecording: " + e.getMessage();
            Log.w(TAG, error);
        } finally {
            try { r.stop(); } catch (IllegalStateException ignored) { }
            r.release();
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord tryOpen(int rate) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        try {
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    /** Linear-interpolation resampling of 16-bit mono PCM; returns output byte count. No anti-alias filter: good enough for speech. */
    static int resample(byte[] in, int inBytes, int inRate, byte[] out, int outRate) {
        int inSamples = inBytes / 2;
        int outSamples = Math.min(out.length / 2, (int) ((long) inSamples * outRate / inRate));
        for (int i = 0; i < outSamples; i++) {
            double pos = (double) i * inRate / outRate;
            int k = (int) pos;
            double frac = pos - k;
            int s0 = sample(in, Math.min(k, inSamples - 1));
            int s1 = sample(in, Math.min(k + 1, inSamples - 1));
            int v = (int) (s0 + (s1 - s0) * frac);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return outSamples * 2;
    }

    private static int sample(byte[] b, int i) {
        return (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
    }

    /** The capture thread notices within one read (62.5 ms) and releases the device itself. */
    @Override
    public void close() {
        current = null;
    }
}
