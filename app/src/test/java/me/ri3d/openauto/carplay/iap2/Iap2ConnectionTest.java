package me.ri3d.openauto.carplay.iap2;

import static me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest.hex;
import static me.ri3d.openauto.carplay.iap2.ControlSessionTest.msg;
import static me.ri3d.openauto.carplay.iap2.ControlSessionTest.param;
import static me.ri3d.openauto.carplay.iap2.Iap2LinkLayerTest.checksum;
import static me.ri3d.openauto.carplay.iap2.Iap2LinkLayerTest.pkt;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest;
import me.ri3d.openauto.carplay.iap2.ControlSession.Authentication;
import me.ri3d.openauto.carplay.iap2.ControlSession.Failure;
import me.ri3d.openauto.carplay.iap2.ControlSession.Identification;
import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.Down;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.Parameters;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.SessionEntry;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.State;

/**
 * {@link Iap2Connection} end to end against a SIMULATED phone whose link and control session are
 * written in this test, over a wire that loses, repeats, damages and splits packets on a seeded
 * schedule. Every acceptance here is SIMULATED: a test result, not evidence about an iPhone.
 */
public class Iap2ConnectionTest {
    private static final String SIMULATED = "SIMULATED identification and authentication (test result: not iPhone acceptance; CarPlay not implemented)";
    private static final Parameters PROPOSAL = BluetoothProfile.linkProposal();
    /** The phone's answer: 3 outstanding, 64-byte packets (54 bytes of payload), 150 ms x30, 10 ms or 1 packet, the proposed control session 10 version 1. */
    private static final String ANSWER = "01" + "03" + "0040" + "0096" + "000A" + "1E" + "01" + "0A0001";
    private static final int SESSION = 10;
    private static final byte[] DETECT = hex("FF550200EE10");
    private static final byte[] ADDRESS = hex("AC123456789A");
    private static final int SYN = 0x80, ACK = 0x40, RST = 0x10;
    private static final long SESSION_TIMEOUT = 10_000;
    private static final BluetoothProfile.Identification IDENTIFICATION = identification("SYNTHETIC accessory");

    @ClassRule
    public static final TemporaryFolder TMP = new TemporaryFolder();
    private static LocalMfiAuthenticator identity;

    long now = 1_000;
    Wire wire = new Wire(0, 0, 0, false, 1);
    Phone phone = new Phone(253);
    private Iap2Connection acc = new Iap2Connection(p -> wire.fromAccessory(p), PROPOSAL, BluetoothProfile.DETECT, identity, IDENTIFICATION, SESSION_TIMEOUT);

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

    @Test
    public void certificateSpanningSeveralPacketsArrivesIntactAndTheSessionGoesOn() {
        acc.start(now, 250);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        assertTrue(SIMULATED, acc.isIdentifiedAndAuthenticated());
        assertArrayEquals("certificate container byte for byte", identity.readCertificate(4096), phone.certificate);
        assertTrue("the certificate response took " + phone.certificatePackets + " packets", phone.certificatePackets >= 5);
        assertTrue("the phone checked the signature", phone.verified);
        assertEquals("the link stayed up throughout", State.UP, acc.link().state());
        assertEquals(SESSION, acc.link().negotiated().sessions.get(0).id);
    }

    @Test
    public void bothOrdersReachReadyOverAFaultyWire() {
        int retransmitted = 0, corrupt = 0, duplicate = 0;
        for (int seed = 1; seed <= 20; seed++) {
            for (boolean identificationFirst : new boolean[]{true, false}) {
                wire = new Wire(0.15, 0.10, 0.10, true, seed);
                phone = new Phone(200 + seed);
                phone.identificationFirst = identificationFirst;
                acc = new Iap2Connection(p -> wire.fromAccessory(p), PROPOSAL, BluetoothProfile.DETECT, identity, IDENTIFICATION, SESSION_TIMEOUT);
                acc.start(now, 240 + seed);
                String what = "seed " + seed + (identificationFirst ? ", identification first" : ", authentication first");
                assertTrue(what + ": " + acc.session() + "; " + acc.link().stats(), run(acc::isIdentifiedAndAuthenticated, 60_000));
                assertTrue(SIMULATED + "; " + what, acc.isIdentifiedAndAuthenticated());
                assertArrayEquals(what, identity.readCertificate(4096), phone.certificate);
                assertTrue(what, phone.verified);
                assertEquals(identificationFirst ? ControlSession.Exchange.IDENTIFICATION : ControlSession.Exchange.AUTHENTICATION,
                        acc.session().firstExchange());
                retransmitted += acc.link().retransmittedPackets;
                corrupt += acc.link().corruptPackets;
                duplicate += acc.link().duplicatePackets;
            }
        }
        assertTrue("faults actually happened: retransmitted " + retransmitted + ", corrupt " + corrupt + ", duplicate " + duplicate,
                retransmitted > 0 && corrupt > 0 && duplicate > 0);
    }

    @Test
    public void staleOrUnsolicitedSuccessCountsForNothing() {
        acc.start(now, 250);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        ControlSession first = acc.session();
        phone.sendRaw(pkt(RST, phone.sendSeq, phone.recvSeq, 0));
        run(() -> acc.link().state() == State.DOWN, 1_000);
        assertEquals(Down.RESET_BY_PHONE, acc.link().downReason());
        assertFalse(acc.isIdentifiedAndAuthenticated());
        assertEquals(Failure.DISCONNECTED, first.failure());
        assertEquals("cleared on failure", Authentication.NOT_STARTED, first.authentication());

        // reconnect; the phone repeats the earlier verdicts instead of running the exchanges
        phone = new Phone(77);
        phone.autoStart = false;
        acc.start(now, 20);
        assertTrue(run(() -> phone.synced, 1_000));
        ControlSession second = acc.session();
        assertNotSame("a new session per link", first, second);
        assertEquals(Identification.NOT_STARTED, second.identification());
        phone.send(msg(ControlSession.AUTHENTICATION_SUCCEEDED));
        phone.send(msg(ControlSession.IDENTIFICATION_ACCEPTED));
        run(() -> acc.link().state() == State.DOWN, 2_000);
        assertEquals(Failure.UNEXPECTED_MESSAGE, second.failure());
        assertFalse(acc.isIdentifiedAndAuthenticated());
        assertEquals("the failed session took the link down", Down.CLOSED, acc.link().downReason());
        assertEquals("with RST", 1, phone.resets);

        phone = new Phone(9);
        acc.start(now, 30);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        assertTrue(SIMULATED + " only after both verdicts of the new session", acc.isIdentifiedAndAuthenticated());
    }

    @Test
    public void silencedLinkAndWithheldVerdictsEndTheConnection() {
        phone.autoStart = false;
        acc.start(now, 250);
        assertTrue(run(() -> phone.synced, 1_000));
        wire.dropToPhone = true; // from here on nothing the accessory sends arrives
        phone.send(msg(ControlSession.START_IDENTIFICATION));
        assertTrue(run(() -> acc.link().state() == State.DOWN, 10_000));
        assertEquals("the answer was sent but never acknowledged", Identification.NOT_STARTED, acc.session().identification());
        assertTrue(acc.session().failureDetail(), acc.session().failureDetail().contains("identification INFORMATION_SENT"));
        assertEquals(Down.RETRANSMISSION_LIMIT, acc.link().downReason());
        assertEquals(Failure.DISCONNECTED, acc.session().failure());

        wire = new Wire(0, 0, 0, false, 1);
        phone = new Phone(3);
        phone.withholdVerdicts = true;
        acc.start(now, 60);
        assertTrue(run(() -> acc.link().state() == State.DOWN, SESSION_TIMEOUT + 5_000));
        assertEquals("every link packet acknowledged, but no verdict", Failure.TIMEOUT, acc.session().failure());
        assertEquals(Down.CLOSED, acc.link().downReason());
        assertFalse(acc.isIdentifiedAndAuthenticated());
    }

    @Test
    public void disconnectHalfwayThroughTheCertificateStartsOverCleanly() {
        phone.autoStart = false;
        acc.start(now, 250);
        assertTrue(run(() -> phone.synced, 1_000));
        phone.send(msg(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE));
        wire.dropToAccessory = true; // the phone's acknowledgements get lost: the accessory stalls after one window
        assertTrue(run(() -> phone.streamPackets >= 2 && acc.session().authentication() == Authentication.CERTIFICATE_SENT, 1_000));
        acc.transportClosed();
        assertEquals(Down.DISCONNECTED, acc.link().downReason());
        assertEquals(Failure.DISCONNECTED, acc.session().failure());
        assertNull("the phone never got a whole certificate", phone.certificate);

        wire = new Wire(0, 0, 0, false, 1); // the cable is back
        phone = new Phone(150);
        acc.start(now, 5);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        assertTrue(SIMULATED, acc.isIdentifiedAndAuthenticated());
        assertArrayEquals(identity.readCertificate(4096), phone.certificate);
    }

    @Test
    public void transportFailureWhileAnsweringLeavesNoProgressBehind() {
        phone.autoStart = false;
        acc.start(now, 250);
        assertTrue(run(() -> phone.synced, 1_000));
        wire.failAccessoryWrites = true;
        phone.send(msg(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE));
        assertTrue(run(() -> acc.link().state() == State.DOWN, 1_000));
        assertEquals(Down.TRANSPORT_ERROR, acc.link().downReason());
        assertEquals(Failure.DISCONNECTED, acc.session().failure());
        assertEquals(Authentication.NOT_STARTED, acc.session().authentication());
        assertFalse(acc.isIdentifiedAndAuthenticated());
    }

    @Test
    public void refusedAuthenticationClosesTheLinkWithoutRetry() {
        phone.refuseSignature = true;
        acc.start(now, 250);
        assertTrue(run(() -> acc.link().state() == State.DOWN, 5_000));
        assertEquals(Failure.AUTHENTICATION_FAILED, acc.session().failure());
        assertEquals(Down.CLOSED, acc.link().downReason());
        assertEquals(1, phone.resets);
        int seen = phone.stream.size();
        run(() -> false, 2_000);
        assertEquals("nothing sent after the refusal", seen, phone.stream.size());
    }

    /** The phone's own parser checks every field the Bluetooth profile sends, and that nothing else is advertised. */
    @Test
    public void identificationAdvertisesOnlyWhatIsImplemented() {
        acc.start(now, 250);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        assertEquals(1, phone.identifications.size());
        List<int[]> ids = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        parameters(phone.identifications.get(0), 6, ids, values);
        int[] expected = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 17};
        assertEquals("parameter IDs in order; no External Accessory (10), team ID (11), USB (14-16), HID (18), vehicle (20, 21) or CarPlay (24)",
                expected.length, ids.size());
        String[] texts = {"SYNTHETIC accessory", "OA-TEST", "OpenAuto test", "SYNTH-0001", "0.4.0", "1"};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], ids.get(i)[0]);
        for (int i = 0; i < texts.length; i++) assertArrayEquals("text " + i + " with its terminating zero", nul(texts[i]), values.get(i));
        assertEquals("nothing sent beyond identification and authentication", 0, values.get(6).length);
        assertEquals("nothing received beyond identification and authentication", 0, values.get(7).length);
        assertArrayEquals("power providing capability: none", new byte[]{0}, values.get(8));
        assertArrayEquals("maximum current drawn: 0 mA", new byte[]{0, 0}, values.get(9));
        assertArrayEquals(nul("en"), values.get(10));
        assertArrayEquals(nul("en"), values.get(11));
        List<int[]> subIds = new ArrayList<>();
        List<byte[]> subValues = new ArrayList<>();
        byte[] component = values.get(12);
        parameters(component, 0, subIds, subValues);
        assertEquals(4, subIds.size());
        assertArrayEquals("component identifier", new byte[]{0, 0}, subValues.get(0));
        assertArrayEquals(nul("Bluetooth"), subValues.get(1));
        assertEquals("supports iAP2: a flag without a value", 0, subValues.get(2).length);
        assertArrayEquals("the receiver's own Bluetooth address", ADDRESS, subValues.get(3));
        for (int i = 0; i < 4; i++) assertEquals(i, subIds.get(i)[0]);
    }

    @Test
    public void rejectedNameIsCorrectedToTheDefaultAndAccepted() {
        acc = new Iap2Connection(p -> wire.fromAccessory(p), PROPOSAL, BluetoothProfile.DETECT, identity, identification("Küchenradio ünd Co"), SESSION_TIMEOUT);
        phone.rejectOnce = new int[]{0};
        acc.start(now, 250);
        assertTrue(run(acc::isIdentifiedAndAuthenticated, 5_000));
        assertTrue(SIMULATED, acc.isIdentifiedAndAuthenticated());
        assertEquals("rejected once, corrected once", 2, phone.identifications.size());
        List<int[]> ids = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        parameters(phone.identifications.get(1), 6, ids, values);
        assertArrayEquals("the default name", nul("OpenAuto"), values.get(0));
        List<int[]> firstIds = new ArrayList<>();
        List<byte[]> firstValues = new ArrayList<>();
        parameters(phone.identifications.get(0), 6, firstIds, firstValues);
        for (int i = 1; i < values.size(); i++) assertArrayEquals("only the name changed, parameter " + ids.get(i)[0], firstValues.get(i), values.get(i));
    }

    @Test
    public void rejectedMandatoryFieldsEndTheSessionClearly() {
        Object[][] cases = {
                {new int[]{17}, "BluetoothTransportComponent (17) rejected: mandatory for this transport profile", IDENTIFICATION},
                {new int[]{1}, "ModelIdentifier (1) rejected", IDENTIFICATION},
                {new int[]{12}, "CurrentLanguage (12) rejected", IDENTIFICATION},
                {new int[]{0, 6}, "MessagesSentByAccessory (6) rejected", IDENTIFICATION},
                {new int[]{0}, "already is the default", identification("OpenAuto")},
        };
        for (Object[] c : cases) {
            wire = new Wire(0, 0, 0, false, 1);
            phone = new Phone(253);
            phone.rejectOnce = (int[]) c[0];
            acc = new Iap2Connection(p -> wire.fromAccessory(p), PROPOSAL, BluetoothProfile.DETECT, identity, (IdentificationPayload) c[2], SESSION_TIMEOUT);
            acc.start(now, 250);
            assertTrue(run(() -> acc.link().state() == State.DOWN, 5_000));
            assertEquals(Failure.IDENTIFICATION_REJECTED, acc.session().failure());
            assertTrue(acc.session().failureDetail(), acc.session().failureDetail().contains((String) c[1]));
            assertEquals("no second attempt", 1, phone.identifications.size());
            assertEquals("the link closed with RST", 1, phone.resets);
        }
    }

    private static BluetoothProfile.Identification identification(String name) {
        return new BluetoothProfile.Identification(name, "OA-TEST", "OpenAuto test", "SYNTH-0001", "0.4.0", "1", ADDRESS);
    }

    /** Top-level (or group) parameters from {@code off} on, read with this test's own code: {id} and value per parameter. */
    static void parameters(byte[] b, int off, List<int[]> ids, List<byte[]> values) {
        while (off < b.length) {
            int len = (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
            ids.add(new int[]{(b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF)});
            values.add(Arrays.copyOfRange(b, off + 4, off + len));
            off += len;
        }
    }

    private static byte[] nul(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(b, b.length + 1);
    }

    // ---- simulation ----

    /** Delivers everything in flight, then advances time in 5 ms steps until done or the time is up. */
    private boolean run(BooleanSupplier done, long millis) {
        long end = now + millis;
        while (true) {
            for (int guard = 0; guard < 100_000 && !(wire.toPhone.isEmpty() && wire.toAccessory.isEmpty()); guard++) {
                byte[] b = wire.toAccessory.poll();
                if (b != null) acc.receive(b, 0, b.length, now);
                b = wire.toPhone.poll();
                if (b != null) phone.receive(b);
            }
            if (done.getAsBoolean()) return true;
            if (now >= end) return false;
            now += 5;
            acc.poll(now);
            phone.poll();
        }
    }

    /** Packets in both directions, each one maybe lost, repeated, damaged in one bit, or split into pieces. */
    static final class Wire {
        final ArrayDeque<byte[]> toPhone = new ArrayDeque<>(), toAccessory = new ArrayDeque<>();
        final double loss, duplicate, corrupt;
        final boolean fragment;
        final Random random;
        boolean dropToPhone, dropToAccessory, failAccessoryWrites;

        Wire(double loss, double duplicate, double corrupt, boolean fragment, long seed) {
            this.loss = loss;
            this.duplicate = duplicate;
            this.corrupt = corrupt;
            this.fragment = fragment;
            this.random = new Random(seed);
        }

        void fromAccessory(byte[] p) throws IOException {
            if (failAccessoryWrites) throw new IOException("cable pulled");
            if (!dropToPhone) carry(p, toPhone);
        }

        void fromPhone(byte[] p) {
            if (!dropToAccessory) carry(p, toAccessory);
        }

        private void carry(byte[] p, ArrayDeque<byte[]> q) {
            if (random.nextDouble() < loss) return;
            byte[] copy = p.clone();
            if (random.nextDouble() < corrupt) copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
            split(copy, q);
            if (random.nextDouble() < duplicate) split(p.clone(), q);
        }

        private void split(byte[] p, ArrayDeque<byte[]> q) {
            int off = 0;
            while (off < p.length) {
                int n = fragment ? 1 + random.nextInt(Math.min(p.length - off, 12)) : p.length - off;
                q.add(Arrays.copyOfRange(p, off, off + n));
                off += n;
            }
        }
    }

    /**
     * The phone, written for this test: its link answers the SYN, acknowledges every in-order packet
     * at once and resends its own unacknowledged packets after 150 ms; its control session runs the
     * exchanges from the phone's side and checks the signature with the host JCA.
     */
    final class Phone {
        final int initialSeq;
        int sendSeq, recvSeq;
        boolean synced, started, certificateRequested, autoStart = true, identificationFirst = true, withholdVerdicts, refuseSignature, verified;
        int resets, streamPackets, certificatePackets;
        int[] rejectOnce;
        final List<byte[]> identifications = new ArrayList<>();
        byte[] certificate, challenge;
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        private final List<int[]> spans = new ArrayList<>();
        private final ArrayDeque<int[]> inFlight = new ArrayDeque<>(); // {seq, sentAt}
        private final List<byte[]> inFlightPayloads = new ArrayList<>();
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private final byte[] buf = new byte[1 << 16];
        private int bufLen, parsed;

        Phone(int initialSeq) {
            this.initialSeq = initialSeq & 0xFF;
            this.sendSeq = this.initialSeq;
        }

        void receive(byte[] b) {
            System.arraycopy(b, 0, buf, bufLen, b.length);
            bufLen += b.length;
            int pos = 0;
            while (true) {
                while (pos < bufLen && (buf[pos] & 0xFF) != 0xFF) pos++;
                if (bufLen - pos >= 2 && (buf[pos + 1] & 0xFF) == 0x55) { // detection: answer with the same bytes
                    if (bufLen - pos < DETECT.length) break;
                    if (Arrays.equals(Arrays.copyOfRange(buf, pos, pos + DETECT.length), DETECT)) sendRaw(DETECT.clone());
                    pos += DETECT.length;
                    continue;
                }
                if (bufLen - pos < 9) break;
                int len = (buf[pos + 2] & 0xFF) << 8 | (buf[pos + 3] & 0xFF);
                if ((buf[pos + 1] & 0xFF) != 0x5A || checksum(buf, pos, 8) != buf[pos + 8] || len < 9 || len == 10 || len > 64) { // 64: what this phone negotiated
                    pos++;
                    continue;
                }
                if (bufLen - pos < len) break;
                byte[] payload = len > 9 ? Arrays.copyOfRange(buf, pos + 9, pos + len - 1) : new byte[0];
                boolean intact = len == 9 || checksum(payload, 0, payload.length) == buf[pos + len - 1];
                int control = buf[pos + 4] & 0xFF, seq = buf[pos + 5] & 0xFF, ack = buf[pos + 6] & 0xFF, session = buf[pos + 7] & 0xFF;
                pos += len;
                if (intact) packet(control, seq, ack, session, payload);
            }
            System.arraycopy(buf, pos, buf, 0, bufLen - pos);
            bufLen -= pos;
        }

        private void packet(int control, int seq, int ack, int session, byte[] payload) {
            if ((control & RST) != 0) {
                resets++;
                return;
            }
            if ((control & SYN) != 0) {
                recvSeq = seq;
                if (inFlight.isEmpty() && !synced) inFlight.add(new int[]{initialSeq, (int) now});
                sendRaw(pkt(SYN | ACK, initialSeq, recvSeq, 0, hex(ANSWER))); // also answers a repeated SYN
                return;
            }
            if ((control & ACK) != 0) acknowledge(ack);
            if (payload.length == 0) return;
            if (session == SESSION && seq == ((recvSeq + 1) & 0xFF)) {
                recvSeq = seq;
                spans.add(new int[]{stream.size(), payload.length});
                stream.write(payload, 0, payload.length);
                streamPackets++;
                sendRaw(pkt(ACK, sendSeq, recvSeq, 0));
                readMessages();
            } else {
                sendRaw(pkt(ACK, sendSeq, recvSeq, 0));
            }
        }

        private void acknowledge(int ack) {
            if (inFlight.isEmpty()) return;
            int n = ((ack - inFlight.peekFirst()[0]) & 0xFF) + 1;
            if (n > inFlight.size()) return;
            for (int i = 0; i < n; i++) {
                if (inFlight.removeFirst()[0] != initialSeq || synced) inFlightPayloads.remove(0);
                else synced = true;
            }
            if (synced && !started && autoStart) {
                started = true;
                send(msg(identificationFirst ? ControlSession.START_IDENTIFICATION : ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE));
            }
            pump();
        }

        void send(byte[] message) {
            for (int off = 0; off < message.length; off += 54) queue.add(Arrays.copyOfRange(message, off, Math.min(message.length, off + 54)));
            pump();
        }

        private void pump() {
            while (synced && inFlight.size() < 3 && !queue.isEmpty()) {
                byte[] payload = queue.removeFirst();
                sendSeq = (sendSeq + 1) & 0xFF;
                inFlight.add(new int[]{sendSeq, (int) now});
                inFlightPayloads.add(payload);
                sendRaw(pkt(ACK, sendSeq, recvSeq, SESSION, payload));
            }
        }

        void poll() {
            int[] oldest = inFlight.peekFirst();
            if (oldest == null || now - oldest[1] < 150) return;
            int i = 0;
            for (int[] o : inFlight) {
                o[1] = (int) now;
                if (!synced && o[0] == initialSeq) sendRaw(pkt(SYN | ACK, initialSeq, recvSeq, 0, hex(ANSWER)));
                else sendRaw(pkt(ACK, o[0], recvSeq, SESSION, inFlightPayloads.get(i++)));
            }
        }

        void sendRaw(byte[] packet) {
            wire.fromPhone(packet);
        }

        /** The phone's control session: whole accessory messages, read with this test's own parser. */
        private void readMessages() {
            byte[] all = stream.toByteArray();
            while (all.length - parsed >= 6) {
                int len = (all[parsed + 2] & 0xFF) << 8 | (all[parsed + 3] & 0xFF);
                if (all.length - parsed < len) return;
                byte[] m = Arrays.copyOfRange(all, parsed, parsed + len);
                int id = (m[4] & 0xFF) << 8 | (m[5] & 0xFF);
                if (id == ControlSession.IDENTIFICATION_INFORMATION) {
                    identifications.add(m);
                    if (rejectOnce != null) {
                        byte[][] named = new byte[rejectOnce.length][];
                        for (int i = 0; i < named.length; i++) named[i] = param(rejectOnce[i], new byte[0]);
                        rejectOnce = null;
                        send(msg(ControlSession.IDENTIFICATION_REJECTED, named));
                        parsed += len;
                        continue;
                    }
                    if (!withholdVerdicts) send(msg(ControlSession.IDENTIFICATION_ACCEPTED));
                    if (identificationFirst && !certificateRequested && !withholdVerdicts) {
                        certificateRequested = true;
                        send(msg(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE));
                    }
                } else if (id == ControlSession.AUTHENTICATION_CERTIFICATE) {
                    certificate = ControlSessionTest.SimulatedPhone.onlyParameter0(m, id);
                    for (int[] s : spans) if (s[0] < parsed + len && s[0] + s[1] > parsed) certificatePackets++;
                    challenge = new byte[32];
                    new Random(parsed).nextBytes(challenge);
                    send(msg(ControlSession.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, challenge)));
                } else if (id == ControlSession.AUTHENTICATION_RESPONSE) {
                    try {
                        verified = ControlSessionTest.SimulatedPhone.verifies(certificate, challenge, ControlSessionTest.SimulatedPhone.onlyParameter0(m, id));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    if (!withholdVerdicts) {
                        send(msg(verified && !refuseSignature ? ControlSession.AUTHENTICATION_SUCCEEDED : ControlSession.AUTHENTICATION_FAILED));
                    }
                    if (!identificationFirst && verified && !refuseSignature) send(msg(ControlSession.START_IDENTIFICATION));
                }
                parsed += len;
            }
        }
    }
}
