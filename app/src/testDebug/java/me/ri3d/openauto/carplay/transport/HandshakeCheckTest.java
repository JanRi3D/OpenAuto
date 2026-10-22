package me.ri3d.openauto.carplay.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest;
import me.ri3d.openauto.carplay.iap2.BluetoothProfile;
import me.ri3d.openauto.carplay.iap2.Iap2StreamTest;

/**
 * The handshake check's observations: against the SIMULATED phone of {@link Iap2StreamTest} behind
 * a loopback socket, and against endpoints that refuse, block or are cancelled. Every acceptance
 * here is SIMULATED: a test result, not evidence about an iPhone.
 */
public class HandshakeCheckTest {
    private static final BluetoothProfile.Identification IDENTIFICATION = new BluetoothProfile.Identification(
            "SYNTHETIC accessory", "OA-TEST", "OpenAuto test", "SYNTH-0001", "0.4.0", "1", new byte[]{(byte) 0xAC, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A});

    @ClassRule
    public static final TemporaryFolder TMP = new TemporaryFolder();
    private static LocalMfiAuthenticator identity;

    @BeforeClass
    public static void syntheticIdentity() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        File dir = TMP.newFolder();
        Files.write(new File(dir, LocalMfiAuthenticator.KEY_FILE).toPath(), kp.getPrivate().getEncoded());
        Files.write(new File(dir, LocalMfiAuthenticator.CERTIFICATE_FILE).toPath(),
                LocalMfiAuthenticatorTest.pkcs7(LocalMfiAuthenticatorTest.certificate(kp.getPublic().getEncoded())));
        identity = LocalMfiAuthenticator.load(dir);
    }

    @Test(timeout = 20_000)
    public void completedHandshakeIsReportedLineByLineAndTheLimitClosesTheLink() throws Exception {
        final Iap2StreamTest.LoopbackPhone phone = new Iap2StreamTest.LoopbackPhone();
        try {
            RfcommCheck.Endpoint e = new RfcommCheck.Endpoint() {
                @Override
                public String open() {
                    return "fake peer";
                }

                @Override
                public InputStream input() throws IOException {
                    return phone.accessorySide.getInputStream();
                }

                @Override
                public OutputStream output() throws IOException {
                    return phone.accessorySide.getOutputStream();
                }

                @Override
                public void close() {
                    try {
                        phone.accessorySide.close();
                    } catch (IOException ignored) {
                    }
                }
            };
            List<String> r = new HandshakeCheck(e, identity, IDENTIFICATION).run(1_500);
            String all = String.join("\n", r);
            assertTrue(all, r.get(0).startsWith("socket: connected after ") && r.get(0).endsWith("(fake peer)"));
            assertTrue(all, r.get(1).startsWith("link: DOWN after ") && r.get(1).contains("ended because time limit of "));
            assertTrue(all, r.get(2).startsWith("negotiated: version 1, 3 outstanding, 64 B packets"));
            assertTrue(all, r.get(3).startsWith("link packets: corrupt 0, duplicate 0"));
            assertTrue(all, r.get(4).startsWith("session: iAP2 control session: ") && r.get(4).contains("failed DISCONNECTED: link lost; identification ACCEPTED, authentication SUCCEEDED")
                    && r.get(4).endsWith("; the phone started with IDENTIFICATION"));
            assertTrue(all, r.get(5).startsWith("verdict: identification accepted and authentication succeeded with the SYNTHETIC identity. Unexpected from an iPhone"));
            assertEquals(all, "after the handshake: no other messages from the phone", r.get(6));
            assertTrue(all, r.get(7).startsWith("note: stopped at the time limit"));
            assertEquals(all, 8, r.size());
        } finally {
            phone.close();
        }
    }

    @Test(timeout = 20_000)
    public void refusedConnectionStartsNoLink() {
        Blocking e = new Blocking();
        e.openFails = new IOException("Service discovery failed");
        List<String> r = new HandshakeCheck(e, identity, IDENTIFICATION).run(5_000);
        assertEquals(2, r.size());
        assertTrue(r.get(0), r.get(0).startsWith("socket: not connected after ") && r.get(0).endsWith("java.io.IOException: Service discovery failed"));
        assertEquals("link: not started", r.get(1));
        assertEquals("nothing written", 0, e.written.size());
    }

    @Test(timeout = 20_000)
    public void cancelClosesABlockedConnectAndASilentPeer() throws Exception {
        final Blocking e = new Blocking();
        e.blockOpen = true;
        final HandshakeCheck check = new HandshakeCheck(e, identity, IDENTIFICATION);
        new Thread(() -> {
            try {
                e.opened.await(10, TimeUnit.SECONDS);
                check.cancel();
            } catch (InterruptedException ignored) {
            }
        }).start();
        List<String> r = check.run(10_000);
        assertTrue(r.get(0), r.get(0).startsWith("socket: not connected after ") && r.get(0).contains("stopped by Cancel"));
        assertEquals("no time-limit note after Cancel", 2, r.size());

        // a peer that connects but never answers the detection bytes: Cancel ends the run at once
        final Blocking silent = new Blocking();
        final HandshakeCheck second = new HandshakeCheck(silent, identity, IDENTIFICATION);
        new Thread(() -> {
            try {
                silent.opened.await(10, TimeUnit.SECONDS);
                Thread.sleep(200);
                second.cancel();
            } catch (InterruptedException ignored) {
            }
        }).start();
        long start = System.nanoTime();
        r = second.run(10_000);
        assertTrue("returned within " + (System.nanoTime() - start) / 1_000_000 + " ms", (System.nanoTime() - start) / 1_000_000 < 5_000);
        String all = String.join("\n", r);
        assertTrue(all, r.get(1).startsWith("link: DOWN after ") && r.get(1).endsWith("ended because stopped by Cancel"));
        assertTrue(all, r.get(3).startsWith("session: none, the link never synchronized"));
        assertTrue("only the detection bytes went out: " + silent.written.size() + " bytes", silent.written.size() > 0 && silent.written.size() % BluetoothProfile.DETECT.length == 0);
        assertFalse(all, all.contains("note: stopped at the time limit"));
    }

    @Test
    public void cancelBeforeTheRunNeverOpens() {
        Blocking e = new Blocking();
        HandshakeCheck check = new HandshakeCheck(e, identity, IDENTIFICATION);
        check.cancel();
        List<String> r = check.run(5_000);
        assertEquals("never opened", 1, e.opened.getCount());
        assertTrue(r.get(0), r.get(0).contains("stopped by Cancel"));
    }

    /** An endpoint that blocks like Android's sockets until it is closed. */
    private static final class Blocking implements RfcommCheck.Endpoint {
        final CountDownLatch opened = new CountDownLatch(1), closed = new CountDownLatch(1);
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        boolean blockOpen;
        IOException openFails;

        @Override
        public String open() throws IOException {
            opened.countDown();
            if (openFails != null) throw openFails;
            if (blockOpen) {
                awaitClose();
                throw new IOException("socket closed");
            }
            return "silent peer";
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
                    awaitClose();
                    throw new IOException("socket closed");
                }
            };
        }

        @Override
        public OutputStream output() {
            return written;
        }

        @Override
        public void close() {
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
