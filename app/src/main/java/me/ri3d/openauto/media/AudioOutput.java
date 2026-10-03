package me.ri3d.openauto.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.aa.Media;

/**
 * PCM playback through AudioTrack (streaming mode). The reader thread queues pooled chunks; a
 * player thread writes them so AudioTrack's blocking write never stalls protocol reads.
 */
public final class AudioOutput implements Media.AudioOut {
    private static final String TAG = "AudioOutput";
    private static final int QUEUE = 16, CHUNK = 8192;

    private static final class Chunk {
        byte[] data = new byte[CHUNK];
        int len;
    }

    private final String name;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(QUEUE);
    private final ArrayBlockingQueue<Chunk> pool = new ArrayBlockingQueue<>(QUEUE);
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean ducked;
    public volatile long bytesPlayed, chunksDropped, underruns;

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
        int size = Math.max(min * 2, sampleRate * channels * 2 / 4); // >= 250 ms
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
        AudioTrack t = track;
        try {
            while (running) {
                Chunk c = queue.poll(100, TimeUnit.MILLISECONDS);
                if (c == null) continue;
                if (t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) t.play();
                int off = 0;
                while (off < c.len && running) {
                    int n = t.write(c.data, off, c.len - off);
                    if (n < 0) {
                        underruns++;
                        break;
                    }
                    off += n;
                }
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

    @Override
    public synchronized void stop() {
        AudioTrack t = track;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
        } catch (IllegalStateException ignored) {
        }
        Chunk c;
        while ((c = queue.poll()) != null) pool.offer(c);
    }

    @Override
    public synchronized void close() {
        if (track != null && bytesPlayed > 0) Log.i(TAG, name + ": played " + bytesPlayed / 1024 + " KiB, dropped " + chunksDropped + " chunks, write errors " + underruns);
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
