package me.ri3d.openauto.carplay.iap2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest;
import me.ri3d.openauto.carplay.iap2.ControlSession.Failure;
import me.ri3d.openauto.carplay.iap2.Iap2ConnectionTest.Wire;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.Down;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.State;

/**
 * {@link Iap2Stream} over real sockets (TCP loopback standing in for RFCOMM), with real threads and
 * the system clock, against the SIMULATED phone of {@link Iap2ConnectionTest} driven on its own
 * thread. Every acceptance here is SIMULATED: a test result, not evidence about an iPhone.
 */
public class Iap2StreamTest {
    private static final long SESSION_TIMEOUT = 10_000;
    private static final BluetoothProfile.Identification IDENTIFICATION = new BluetoothProfile.Identification(
            "SYNTHETIC accessory", "OA-TEST", "OpenAuto test", "SYNTH-0001", "0.4.0", "1", new byte[]{(byte) 0xAC, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A});

    @ClassRule
    public static final TemporaryFolder TMP = new TemporaryFolder();
    private static LocalMfiAuthenticator identity;

    private LoopbackPhone phone;
    private Iap2Stream stream;

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

    @Before
    public void connect() throws IOException {
        phone = new LoopbackPhone(new Wire(0, 0, 0, false, 1));
        stream = new Iap2Stream(phone.accessorySide.getInputStream(), phone.accessorySide.getOutputStream(),
                BluetoothProfile.linkProposal(), BluetoothProfile.DETECT, identity, IDENTIFICATION, SESSION_TIMEOUT);
    }

    @After
    public void disconnect() throws Exception {
        phone.close();
    }

    @Test(timeout = 20_000)
    public void handshakeCompletesOverSocketsAndStopClosesTheLinkWithReset() throws Exception {
        stopWhen(() -> stream.connection().isIdentifiedAndAuthenticated());
        assertEquals("stopped", stream.run(15_000));
        assertTrue("SIMULATED identification and authentication (not iPhone acceptance)", stream.wasIdentifiedAndAuthenticated());
        assertFalse("cleared with the link, as in every other session", stream.connection().isIdentifiedAndAuthenticated());
        assertTrue(stream.connection().session().toString(), stream.connection().session().failureDetail().endsWith("identification ACCEPTED, authentication SUCCEEDED"));
        assertTrue(phone.host.phone.verified);
        assertArrayEquals(identity.readCertificate(4096), phone.host.phone.certificate);
        assertEquals(State.DOWN, stream.connection().link().state());
        assertEquals(Down.CLOSED, stream.connection().link().downReason());
        assertTrue("the phone got the RST", phone.await(() -> phone.host.phone.resets == 1, 2_000));
        assertEquals(0, stream.connection().unhandledMessages);
    }

    @Test(timeout = 20_000)
    public void faultyWireIsRecoveredWithRealTiming() throws Exception {
        phone.close();
        phone = new LoopbackPhone(new Wire(0.10, 0.10, 0.10, true, 7));
        stream = new Iap2Stream(phone.accessorySide.getInputStream(), phone.accessorySide.getOutputStream(),
                BluetoothProfile.linkProposal(), BluetoothProfile.DETECT, identity, IDENTIFICATION, SESSION_TIMEOUT);
        stopWhen(() -> stream.connection().isIdentifiedAndAuthenticated());
        assertEquals(stream.connection().session() + "; " + stream.connection().link().stats(), "stopped", stream.run(15_000));
        assertTrue(stream.wasIdentifiedAndAuthenticated());
        assertTrue(phone.host.phone.verified);
        assertTrue(stream.connection().link().stats(), stream.connection().link().corruptPackets + stream.connection().link().duplicatePackets > 0);
    }

    @Test(timeout = 20_000)
    public void timeLimitEndsALiveLinkWithReset() throws Exception {
        phone.host.phone.autoStart = false;
        long start = System.nanoTime();
        String why = stream.run(400);
        long took = (System.nanoTime() - start) / 1_000_000;
        assertEquals("time limit of 400 ms reached", why);
        assertTrue("took " + took + " ms", took >= 390 && took < 3_000); // millisecond truncation on both clocks
        assertEquals(Down.CLOSED, stream.connection().link().downReason());
        assertTrue("synchronized before the limit", phone.host.phone.synced);
        assertTrue(phone.await(() -> phone.host.phone.resets == 1, 2_000));
        assertFalse(stream.wasIdentifiedAndAuthenticated());
    }

    @Test(timeout = 20_000)
    public void peerClosingTheSocketTakesTheLinkDown() throws Exception {
        phone.host.phone.autoStart = false;
        new Thread(() -> {
            phone.await(() -> phone.host.phone.synced, 5_000);
            phone.hangUp();
        }).start();
        String why = stream.run(15_000);
        assertEquals("link down (DISCONNECTED: transport closed)", why);
        assertEquals("end of stream", stream.endOfInput());
        assertEquals(Failure.DISCONNECTED, stream.connection().session().failure());
    }

    @Test(timeout = 20_000)
    public void messagesAfterTheHandshakeAreKeptForTheRecord() throws Exception {
        stopWhen(() -> stream.connection().unhandledMessages >= 2);
        new Thread(() -> {
            phone.await(() -> stream.connection().isIdentifiedAndAuthenticated(), 10_000);
            phone.send(ControlSessionTest.msg(0x4E04)); // whatever the phone sends next: counted and kept, not answered
            phone.send(ControlSessionTest.msg(0x4E09));
        }).start();
        assertEquals("stopped", stream.run(15_000));
        assertEquals(2, stream.connection().unhandled.size());
        assertEquals(0x4E04, stream.connection().unhandled.get(0).id);
        assertEquals(0x4E09, stream.connection().unhandled.get(1).id);
        assertTrue(stream.wasIdentifiedAndAuthenticated());
    }

    private void stopWhen(BooleanSupplier condition) {
        Thread t = new Thread(() -> {
            phone.await(condition, 15_000);
            stream.stop();
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * The SIMULATED phone of {@link Iap2ConnectionTest} behind a loopback socket: a pump thread
     * feeds it what the accessory writes, writes what it (through the faulty wire) sends, advances
     * its clock and polls it. Only the pump thread touches the phone while it runs.
     */
    public static final class LoopbackPhone {
        final Iap2ConnectionTest host = new Iap2ConnectionTest();
        public final Socket accessorySide;
        private final ServerSocket server;
        private final Socket phoneSide;
        private final Thread pump;
        private final long started = System.nanoTime();
        private volatile boolean running = true;
        private volatile byte[] toSend;

        /** A phone behind a clean wire, for tests in other packages. */
        public LoopbackPhone() throws IOException {
            this(new Wire(0, 0, 0, false, 1));
        }

        LoopbackPhone(Wire wire) throws IOException {
            host.wire = wire;
            host.phone = host.new Phone(253);
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            accessorySide = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            phoneSide = server.accept();
            accessorySide.setTcpNoDelay(true);
            phoneSide.setTcpNoDelay(true);
            pump = new Thread(this::pump, "loopback-phone");
            pump.setDaemon(true);
            pump.start();
        }

        /** A control session message from the phone, handed to the pump thread. */
        void send(byte[] message) {
            toSend = message;
            await(() -> toSend == null, 2_000);
        }

        /** Closes the phone's socket: the accessory sees the end of its stream. */
        void hangUp() {
            running = false;
            try {
                phoneSide.close();
            } catch (IOException ignored) {
            }
        }

        boolean await(BooleanSupplier condition, long millis) {
            long end = System.nanoTime() + millis * 1_000_000;
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() >= end) return false;
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return true;
        }

        public void close() throws Exception {
            running = false;
            pump.join(2_000);
            accessorySide.close();
            phoneSide.close();
            server.close();
        }

        private void pump() {
            byte[] buf = new byte[4096];
            try {
                InputStream in = phoneSide.getInputStream();
                OutputStream out = phoneSide.getOutputStream();
                while (running) {
                    host.now = (System.nanoTime() - started) / 1_000_000;
                    int available = in.available();
                    if (available > 0) {
                        int n = in.read(buf, 0, Math.min(available, buf.length));
                        if (n > 0) host.phone.receive(Arrays.copyOf(buf, n));
                    }
                    byte[] m = toSend;
                    if (m != null) {
                        host.phone.send(m);
                        toSend = null;
                    }
                    host.phone.poll();
                    byte[] b;
                    boolean wrote = false;
                    while ((b = host.wire.toAccessory.poll()) != null) {
                        out.write(b);
                        wrote = true;
                    }
                    if (wrote) out.flush();
                    if (available <= 0) Thread.sleep(2);
                }
            } catch (IOException | InterruptedException ignored) {
                // the socket was closed under the pump: the test is over
            }
        }
    }
}
