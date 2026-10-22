package me.ri3d.openauto.carplay.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * One user-started RFCOMM check with one phone: get a connected socket (by connecting to the
 * phone's service or by accepting the phone's connection), send the iAP2 detection bytes and read
 * what comes back, all within one time limit. The socket and the echo are separate observations:
 * a socket that connects says nothing yet about iAP2. The service UUIDs and the detection bytes
 * are hypotheses until a phone answers them.
 *
 * <p>Cancel and the time limit both close the endpoint from another thread; on Android that is
 * what makes a blocked connect, accept or read return. The result then says which of the two
 * stopped the check. A stop at the time limit is no evidence that the phone or the radio cannot
 * support CarPlay.
 */
final class RfcommCheck {
    /** One way to a connected RFCOMM socket. */
    interface Endpoint {
        /** Blocks until a socket is connected and returns who is on the other side. */
        String open() throws IOException;

        InputStream input() throws IOException;

        OutputStream output() throws IOException;

        /** Any thread, any number of times: closes every socket it holds, so blocked calls return. */
        void close();
    }

    /** Reading stops after this many bytes when the detection sequence is not among them. */
    static final int MAX_BYTES = 64;

    private final Endpoint endpoint;
    private final byte[] detect;
    private volatile String stoppedBy;

    RfcommCheck(Endpoint endpoint, byte[] detect) {
        this.endpoint = endpoint;
        this.detect = detect.clone();
    }

    /** Any thread: stops the check and closes the endpoint. */
    void cancel() {
        stop("Cancel");
    }

    private void stop(String why) {
        synchronized (this) {
            if (stoppedBy == null) stoppedBy = why;
        }
        endpoint.close();
    }

    /** Blocks for at most about {@code limitMs}; one line per observation. */
    List<String> run(final long limitMs) {
        List<String> out = new ArrayList<>();
        Timer deadline = new Timer("rfcomm-check-limit", true);
        deadline.schedule(new TimerTask() {
            @Override
            public void run() {
                stop("the " + limitMs + " ms limit");
            }
        }, limitMs);
        long start = System.nanoTime();
        try {
            String peer;
            try {
                if (stoppedBy != null) throw new IOException("not started");
                peer = endpoint.open();
            } catch (IOException | SecurityException e) {
                out.add("socket: not connected after " + since(start) + " ms: " + why(e));
                out.add("detection: not sent");
                out.add("echo: not checked");
                return note(out);
            }
            out.add("socket: connected after " + since(start) + " ms (" + peer + ")");
            try {
                OutputStream o = endpoint.output();
                o.write(detect);
                o.flush();
            } catch (IOException e) {
                out.add("detection: not sent: " + why(e));
                out.add("echo: not checked");
                return note(out);
            }
            long sent = System.nanoTime();
            out.add("detection: sent " + UsbDescriptors.hex(detect, 0, detect.length));
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            String end = null;
            try {
                InputStream in = endpoint.input();
                byte[] buf = new byte[MAX_BYTES];
                while (indexOf(got.toByteArray(), detect) < 0) {
                    if (got.size() >= MAX_BYTES) {
                        end = "stopped reading after " + MAX_BYTES + " bytes";
                        break;
                    }
                    int n = in.read(buf, 0, MAX_BYTES - got.size());
                    if (n < 0) {
                        end = "the other side closed the stream";
                        break;
                    }
                    got.write(buf, 0, n);
                }
            } catch (IOException e) {
                end = why(e);
            }
            byte[] b = got.toByteArray();
            int at = indexOf(b, detect);
            if (at >= 0) {
                out.add("echo: detection sequence received " + since(sent) + " ms after sending" + (at > 0 ? ", after " + at + " other byte(s)" : "")
                        + "; bytes: " + UsbDescriptors.hex(b, 0, b.length));
            } else {
                out.add("echo: not received (" + end + "); " + (b.length == 0 ? "no bytes" : b.length + " byte(s): " + UsbDescriptors.hex(b, 0, b.length)));
            }
            return note(out);
        } finally {
            deadline.cancel();
            endpoint.close();
        }
    }

    private List<String> note(List<String> out) {
        String by = stoppedBy;
        if (by != null && by.startsWith("the ")) {
            out.add("note: stopped at the time limit; this is no evidence that the phone or the radio cannot support CarPlay");
        }
        return out;
    }

    private String why(Exception e) {
        String by = stoppedBy;
        return by != null ? "stopped by " + by + " (" + e + ")" : e.toString();
    }

    private static long since(long start) {
        return (System.nanoTime() - start) / 1000000;
    }

    static int indexOf(byte[] b, byte[] seq) {
        outer:
        for (int i = 0; i + seq.length <= b.length; i++) {
            for (int j = 0; j < seq.length; j++) {
                if (b[i + j] != seq[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
