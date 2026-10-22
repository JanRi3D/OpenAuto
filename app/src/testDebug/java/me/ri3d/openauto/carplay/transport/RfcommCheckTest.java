package me.ri3d.openauto.carplay.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.carplay.iap2.BluetoothProfile;

/**
 * The RFCOMM check against a fake endpoint that blocks the way Android's sockets do until it is
 * closed: socket and echo reported apart, Cancel and the time limit unblocking connect and read,
 * and a stop at the limit never read as a verdict on CarPlay.
 */
public class RfcommCheckTest {
    private static final byte[] DETECT = BluetoothProfile.DETECT;

    @Test
    public void echoSplitAcrossReadsIsRecognised() {
        Fake f = new Fake(new byte[]{(byte) 0xFF, 0x55}, new byte[]{0x02, 0x00, (byte) 0xEE, 0x10});
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertEquals(3, r.size());
        assertTrue(r.get(0), r.get(0).startsWith("socket: connected after ") && r.get(0).endsWith("(fake peer)"));
        assertEquals("detection: sent FF 55 02 00 EE 10", r.get(1));
        assertTrue(r.get(2), r.get(2).startsWith("echo: detection sequence received ") && r.get(2).endsWith("; bytes: FF 55 02 00 EE 10"));
        assertArrayEquals(DETECT, f.written.toByteArray());
        assertTrue("the socket is closed afterwards", f.closes > 0);
    }

    @Test
    public void echoAfterOtherBytesIsFoundAndCounted() {
        Fake f = new Fake(new byte[]{1, 2, (byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10});
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertTrue(r.get(2), r.get(2).contains("after 2 other byte(s); bytes: 01 02 FF 55 02 00 EE 10"));
    }

    @Test
    public void otherBytesThenEndOfStreamIsNoEcho() {
        Fake f = new Fake(new byte[]{(byte) 0xFF, 0x55, 0x02});
        f.endAfterChunks = true;
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertTrue(r.get(0).startsWith("socket: connected"));
        assertEquals("echo: not received (the other side closed the stream); 3 byte(s): FF 55 02", r.get(2));
        assertEquals("no time-limit note without a stop at the limit", 3, r.size());
    }

    @Test
    public void readingStopsAtTheCap() {
        Fake f = new Fake(new byte[200]);
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertTrue(r.get(2), r.get(2).startsWith("echo: not received (stopped reading after 64 bytes); 64 byte(s): 00 00"));
    }

    @Test
    public void refusedConnectionSendsNothing() {
        Fake f = new Fake();
        f.openFails = new IOException("Service discovery failed");
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertEquals(3, r.size());
        assertTrue(r.get(0), r.get(0).startsWith("socket: not connected after ") && r.get(0).endsWith("java.io.IOException: Service discovery failed"));
        assertEquals("detection: not sent", r.get(1));
        assertEquals("echo: not checked", r.get(2));
        assertEquals(0, f.written.size());
    }

    @Test
    public void withheldPermissionIsAnObservation() {
        Fake f = new Fake();
        f.openSecurity = new SecurityException("Need BLUETOOTH_CONNECT permission");
        List<String> r = new RfcommCheck(f, DETECT).run(5000);
        assertTrue(r.get(0), r.get(0).contains("not connected") && r.get(0).contains("BLUETOOTH_CONNECT"));
    }

    @Test
    public void timeLimitClosesABlockedConnect() {
        Fake f = new Fake();
        f.blockOpen = true;
        long start = System.nanoTime();
        List<String> r = new RfcommCheck(f, DETECT).run(300);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("returned after " + ms + " ms", ms >= 250 && ms < 3000);
        assertTrue(r.get(0), r.get(0).contains("not connected") && r.get(0).contains("stopped by the 300 ms limit"));
        assertEquals("note: stopped at the time limit; this is no evidence that the phone or the radio cannot support CarPlay", r.get(r.size() - 1));
        assertEquals(0, f.written.size());
    }

    @Test
    public void cancelClosesABlockedConnectFromAnotherThread() throws Exception {
        Fake f = new Fake();
        f.blockOpen = true;
        final RfcommCheck check = new RfcommCheck(f, DETECT);
        Thread canceller = new Thread(() -> {
            try {
                f.opened.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            check.cancel();
        });
        canceller.start();
        long start = System.nanoTime();
        List<String> r = check.run(30000);
        assertTrue("Cancel, not the 30 s limit", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
        assertTrue(r.get(0), r.get(0).contains("stopped by Cancel"));
        for (String l : r) assertFalse("a cancelled check carries no time-limit note", l.startsWith("note:"));
        canceller.join();
    }

    @Test
    public void timeLimitClosesABlockedReadAndTheSocketObservationStays() {
        Fake f = new Fake(new byte[]{0x7F});
        long start = System.nanoTime();
        List<String> r = new RfcommCheck(f, DETECT).run(300);
        assertTrue("the read was unblocked by the limit", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
        assertTrue(r.get(0).startsWith("socket: connected"));
        assertEquals("detection: sent FF 55 02 00 EE 10", r.get(1));
        assertTrue(r.get(2), r.get(2).startsWith("echo: not received (stopped by the 300 ms limit") && r.get(2).endsWith("; 1 byte(s): 7F"));
        assertTrue(r.get(3).startsWith("note: stopped at the time limit"));
    }

    @Test
    public void cancelBeforeTheRunNeverOpens() {
        Fake f = new Fake();
        RfcommCheck check = new RfcommCheck(f, DETECT);
        check.cancel();
        List<String> r = check.run(5000);
        assertEquals(1, f.opened.getCount());
        assertTrue(r.get(0), r.get(0).contains("not connected") && r.get(0).contains("stopped by Cancel"));
    }

    @Test
    public void indexOfFindsTheSequenceAnywhere() {
        assertEquals(0, RfcommCheck.indexOf(DETECT, DETECT));
        assertEquals(3, RfcommCheck.indexOf(concat(new byte[3], DETECT), DETECT));
        assertEquals(-1, RfcommCheck.indexOf(Arrays.copyOf(DETECT, 5), DETECT));
        assertEquals(-1, RfcommCheck.indexOf(new byte[0], DETECT));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Blocks in open (when asked) and in read once its chunks are used up, until closed, like a BluetoothSocket. */
    private static final class Fake implements RfcommCheck.Endpoint {
        final CountDownLatch opened = new CountDownLatch(1), closed = new CountDownLatch(1);
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final Deque<byte[]> chunks = new ArrayDeque<>();
        volatile int closes;
        boolean blockOpen, endAfterChunks;
        IOException openFails;
        SecurityException openSecurity;

        Fake(byte[]... chunks) {
            this.chunks.addAll(Arrays.asList(chunks));
        }

        @Override
        public String open() throws IOException {
            opened.countDown();
            if (openFails != null) throw openFails;
            if (openSecurity != null) throw openSecurity;
            if (blockOpen) {
                awaitClose();
                throw new IOException("socket closed");
            }
            return "fake peer";
        }

        @Override
        public InputStream input() {
            return new InputStream() {
                @Override
                public int read() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    byte[] c = chunks.poll();
                    if (c == null) {
                        if (endAfterChunks) return -1;
                        awaitClose();
                        throw new IOException("socket closed");
                    }
                    int n = Math.min(len, c.length);
                    System.arraycopy(c, 0, b, off, n);
                    if (n < c.length) chunks.push(Arrays.copyOfRange(c, n, c.length));
                    return n;
                }
            };
        }

        @Override
        public OutputStream output() {
            return written;
        }

        @Override
        public void close() {
            closes++;
            closed.countDown();
        }

        private void awaitClose() throws IOException {
            try {
                if (!closed.await(20, TimeUnit.SECONDS)) throw new IOException("never closed");
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }
}
