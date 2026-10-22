package me.ri3d.openauto.carplay.iap2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.carplay.auth.MfiAuthenticator;

/**
 * One {@link Iap2Connection} over a blocking byte stream, such as a connected RFCOMM socket. The
 * link is not thread-safe, so the thread that calls {@link #run} owns it: a reader thread only
 * hands it the stream's bytes, and it times the link with the system clock, polling it every few
 * milliseconds. Packets are written on the owning thread, as the link produces them.
 *
 * <p>{@link #run} returns when the link is down, when {@link #stop} is called or when its time
 * limit passes; the last two end a live link with RST first. The streams belong to the caller,
 * who closes them afterwards; closing the input is also what unblocks the reader thread.
 */
public final class Iap2Stream implements Iap2LinkLayer.Transport {
    private static final byte[] END = new byte[0];
    /** How long the owning thread waits for bytes before giving the link a chance to retransmit or acknowledge. */
    static final long POLL_MILLIS = 5;

    private final InputStream in;
    private final OutputStream out;
    private final Iap2Connection connection;
    private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
    private volatile boolean stopped, completed;
    private volatile String endOfInput;

    /** @param detect the profile's detection bytes, or null to start with the SYN */
    public Iap2Stream(InputStream in, OutputStream out, Iap2LinkLayer.Parameters proposal, byte[] detect, MfiAuthenticator authenticator,
                      IdentificationPayload identification, long sessionTimeoutMillis) {
        this.in = in;
        this.out = out;
        this.connection = new Iap2Connection(this, proposal, detect, authenticator, identification, sessionTimeoutMillis);
    }

    public Iap2Connection connection() {
        return connection;
    }

    /**
     * Whether the session completed identification and authentication at any point of the run.
     * The session itself clears that state once its link is gone, so this is what is left to
     * report afterwards. With a simulated peer a test result, never iPhone acceptance.
     */
    public boolean wasIdentifiedAndAuthenticated() {
        return completed;
    }

    /** Why the reader stopped reading: "end of stream" or the exception; null while it still reads. */
    public String endOfInput() {
        return endOfInput;
    }

    /**
     * Starts the link and drives it until it is down, {@link #stop} was called or {@code limitMillis}
     * passed. Once only.
     *
     * @return why it returned, in words
     */
    public String run(long limitMillis) {
        if (connection.link().state() != Iap2LinkLayer.State.IDLE) throw new IllegalStateException("already run");
        Thread reader = new Thread(this::read, "iap2-stream-reader");
        reader.setDaemon(true);
        reader.start();
        long start = now();
        connection.start(start, new Random().nextInt(256));
        while (true) {
            if (!completed && connection.isIdentifiedAndAuthenticated()) completed = true;
            // A stop comes first: whoever stops may close the stream in the same breath, and that is still a stop, not the peer hanging up.
            if (stopped) {
                connection.link().close();
                return "stopped";
            }
            if (connection.link().state() == Iap2LinkLayer.State.DOWN) {
                return "link down (" + connection.link().downReason() + ": " + connection.link().downDetail() + ")";
            }
            if (now() - start >= limitMillis) {
                connection.link().close();
                return "time limit of " + limitMillis + " ms reached";
            }
            byte[] chunk;
            try {
                chunk = received.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                continue;
            }
            if (chunk == END) {
                connection.transportClosed();
            } else if (chunk != null) {
                connection.receive(chunk, 0, chunk.length, now());
            }
            connection.poll(now());
        }
    }

    /** Any thread: makes {@link #run} end the link and return. */
    public void stop() {
        stopped = true;
    }

    @Override
    public void write(byte[] packet) throws IOException {
        out.write(packet);
        out.flush();
    }

    private void read() {
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) received.add(Arrays.copyOf(buf, n));
            }
            endOfInput = "end of stream";
        } catch (IOException e) {
            endOfInput = e.toString();
        }
        received.add(END);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }
}
