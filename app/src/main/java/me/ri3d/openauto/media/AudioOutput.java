package me.ri3d.openauto.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.aa.Media;

/**
 * PCM playback through AudioTrack (streaming mode). The reader thread queues pooled chunks; a
 * player thread writes them so AudioTrack's blocking write never stalls protocol reads.
 *
 * Audio shares the link with video, so packets come late whenever the phone or the head unit is
 * busy, and the track's buffer has to absorb that. Android starts a track only once its buffer is
 * full, but a track that ran dry plays every packet the moment it arrives: after one long delay,
 * each small one is heard. A track that runs dry is therefore paused until its buffer is full again.
 */
public final class AudioOutput implements Media.AudioOut {
    private static final String TAG = "AudioOutput";
    private static final int QUEUE = 16, CHUNK = 8192;
    /** Delays up to this long go unheard; it is also how much later the sound starts. */
    private static final int CUSHION_MS = 500;

    private static final class Chunk {
        byte[] data = new byte[CHUNK];
        int len;
    }

    private final String name;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(QUEUE);
    private final ArrayBlockingQueue<Chunk> pool = new ArrayBlockingQueue<>(QUEUE);
    private AudioTrack track;
    private Thread thread;
    private int frameBytes, lowFrames;
    private volatile boolean running;
    private volatile boolean ducked;
    public volatile long bytesPlayed, chunksDropped, writeErrors, refills;

    public AudioOutput(String name) {
        this.name = name;
        for (int i = 0; i < QUEUE; i++) pool.offer(new Chunk());
    }

    @Override
    public synchronized boolean open(int sampleRate, int channels) {
        close();
        int chCfg = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.e(TAG, name + ": unsupported format " + sampleRate + "/" + channels);
            return false;
        }
        frameBytes = channels * 2;
        lowFrames = min / 2 / frameBytes; // the minimum buffer holds two or more mixer periods; a track below one period is starving
        int size = min + sampleRate * frameBytes * CUSHION_MS / 1000; // the minimum covers the device's output latency, which starting a track drains at once
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chCfg, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, name + ": " + e);
            return false;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            track = null;
            return false;
        }
        applyVolume();
        running = true;
        thread = new Thread(this::loop, "audio-" + name);
        thread.start();
        Log.i(TAG, name + ": open " + sampleRate + " Hz x" + channels + ", buffer " + size + " B");
        return true;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioTrack t = track;
        byte[] silence = new byte[CHUNK];
        int written = 0; // frames; wraps around like the playback head position
        try {
            while (running) {
                Chunk c = queue.poll(100, TimeUnit.MILLISECONDS);
                boolean playing = t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
                if (c == null) {
                    // a short sound, or the end of a stream, may never fill a paused track: pad it with silence
                    if (!playing && written != t.getPlaybackHeadPosition()) {
                        int n;
                        while (running && (n = t.write(silence, 0, silence.length)) > 0) written += n / frameBytes;
                        t.play();
                    }
                    continue;
                }
                if (playing && written - t.getPlaybackHeadPosition() < lowFrames) {
                    t.pause(); // ran dry: play on once the buffer is full again
                    refills++;
                    Log.i(TAG, name + ": ran dry, refilling");
                }
                int len = c.len - c.len % frameBytes, off = 0;
                while (off < len && running) {
                    int n = t.write(c.data, off, len - off);
                    if (n < 0) {
                        writeErrors++;
                        break;
                    }
                    off += n;
                    if (off < len) t.play(); // only a track that is not playing takes less: its buffer is full
                }
                written += off / frameBytes;
                bytesPlayed += c.len;
                pool.offer(c);
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException e) {
            Log.e(TAG, name + ": " + e);
        }
    }

    @Override
    public void write(byte[] data, int off, int len) {
        if (!running) return;
        while (len > 0) {
            int part = Math.min(len, CHUNK);
            Chunk c = pool.poll();
            if (c == null) {
                c = queue.poll();
                if (c == null) return;
                chunksDropped++;
            }
            System.arraycopy(data, off, c.data, 0, part);
            c.len = part;
            if (!queue.offer(c)) pool.offer(c);
            off += part;
            len -= part;
        }
    }

    /** The phone ended the stream. What is still buffered plays out, so the end of a prompt is not cut off. */
    @Override
    public void stop() {
    }

    @Override
    public synchronized void close() {
        if (track != null && bytesPlayed > 0) Log.i(TAG, name + ": played " + bytesPlayed / 1024 + " KiB, refilled " + refills + " times, dropped " + chunksDropped + " chunks, write errors " + writeErrors);
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
        Chunk c;
        while ((c = queue.poll()) != null) pool.offer(c);
    }

    @Override
    public void setDucked(boolean d) {
        ducked = d;
        applyVolume();
    }

    @SuppressWarnings("deprecation")
    private void applyVolume() {
        AudioTrack t = track;
        if (t == null) return;
        float v = ducked ? 0.25f : 1f;
        if (Build.VERSION.SDK_INT >= 21) t.setVolume(v); else t.setStereoVolume(v, v);
    }
}
