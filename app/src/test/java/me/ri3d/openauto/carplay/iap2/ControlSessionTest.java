package me.ri3d.openauto.carplay.iap2;

import static me.ri3d.openauto.carplay.iap2.ControlSession.AUTHENTICATION_CERTIFICATE;
import static me.ri3d.openauto.carplay.iap2.ControlSession.AUTHENTICATION_FAILED;
import static me.ri3d.openauto.carplay.iap2.ControlSession.AUTHENTICATION_RESPONSE;
import static me.ri3d.openauto.carplay.iap2.ControlSession.AUTHENTICATION_SUCCEEDED;
import static me.ri3d.openauto.carplay.iap2.ControlSession.IDENTIFICATION_ACCEPTED;
import static me.ri3d.openauto.carplay.iap2.ControlSession.IDENTIFICATION_INFORMATION;
import static me.ri3d.openauto.carplay.iap2.ControlSession.IDENTIFICATION_REJECTED;
import static me.ri3d.openauto.carplay.iap2.ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE;
import static me.ri3d.openauto.carplay.iap2.ControlSession.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE;
import static me.ri3d.openauto.carplay.iap2.ControlSession.START_IDENTIFICATION;
import static me.ri3d.openauto.carplay.iap2.CsmMessageTest.concat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest;
import me.ri3d.openauto.carplay.iap2.ControlSession.Authentication;
import me.ri3d.openauto.carplay.iap2.ControlSession.Failure;
import me.ri3d.openauto.carplay.iap2.ControlSession.Identification;
import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;

/**
 * Identification and authentication against a {@link SimulatedPhone} with a synthetic identity.
 * Every acceptance here is SIMULATED: a scripted phone in this test said yes. It is a test result,
 * not evidence that an iPhone accepts this accessory or its identity.
 */
public class ControlSessionTest {
    private static final long TIMEOUT = 5000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SIMULATED = "SIMULATED acceptance by a scripted phone (test result, not an iPhone)";
    private static final CsmMessage IDENTIFICATION_MESSAGE = new CsmMessage(IDENTIFICATION_INFORMATION,
            new Parameter(0, ascii("SYNTHETIC accessory")), new Parameter(1, ascii("OpenAuto test")));
    /** Without corrections: every rejection of it is final. */
    private static final TestPayload IDENTIFICATION = TestPayload.fixed(IDENTIFICATION_MESSAGE);
    /** The same message written out independently. */
    private static final byte[] IDENTIFICATION_BYTES = msg(IDENTIFICATION_INFORMATION,
            param(0, ascii("SYNTHETIC accessory")), param(1, ascii("OpenAuto test")));

    @ClassRule
    public static final TemporaryFolder TMP = new TemporaryFolder();
    private static LocalMfiAuthenticator identity;

    private long now = 1_000;

    @BeforeClass
    public static void syntheticIdentity() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair kp = g.generateKeyPair();
        File dir = TMP.newFolder();
        Files.write(new File(dir, LocalMfiAuthenticator.KEY_FILE).toPath(), kp.getPrivate().getEncoded());
        Files.write(new File(dir, LocalMfiAuthenticator.CERTIFICATE_FILE).toPath(),
                LocalMfiAuthenticatorTest.pkcs7(LocalMfiAuthenticatorTest.certificate(kp.getPublic().getEncoded())));
        identity = LocalMfiAuthenticator.load(dir);
    }

    @Test
    public void simulatedPhoneAcceptsIdentificationThenAuthentication() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        assertTrue("the accessory waits for the phone", phone.received.isEmpty());
        deliver(s, msg(START_IDENTIFICATION));
        assertArrayEquals("IdentificationInformation as configured", IDENTIFICATION_BYTES, phone.last());
        deliver(s, msg(IDENTIFICATION_ACCEPTED));
        assertEquals(Identification.ACCEPTED, s.identification());
        assertFalse("identification alone is not ready", s.isIdentifiedAndAuthenticated());
        authenticate(s, phone);
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());
        assertNull(s.failure());
        assertEquals(3, phone.received.size());
    }

    @Test
    public void simulatedPhoneMayAlsoAuthenticateBeforeIdentification() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        authenticate(s, phone);
        assertFalse("authentication alone is not ready", s.isIdentifiedAndAuthenticated());
        deliver(s, msg(START_IDENTIFICATION));
        assertArrayEquals(IDENTIFICATION_BYTES, phone.last());
        deliver(s, msg(IDENTIFICATION_ACCEPTED));
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());
    }

    @Test
    public void concatenatedPhoneMessagesAreAnsweredInOrder() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        byte[] challenge = random(32);
        deliver(s, concat(msg(START_IDENTIFICATION), msg(REQUEST_AUTHENTICATION_CERTIFICATE)));
        assertEquals(2, phone.received.size());
        assertArrayEquals(IDENTIFICATION_BYTES, phone.received.get(0));
        byte[] container = SimulatedPhone.onlyParameter0(phone.received.get(1), AUTHENTICATION_CERTIFICATE);
        deliver(s, concat(msg(IDENTIFICATION_ACCEPTED), msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, challenge))));
        assertTrue(SimulatedPhone.verifies(container, challenge, SimulatedPhone.onlyParameter0(phone.last(), AUTHENTICATION_RESPONSE)));
        assertFalse(s.isIdentifiedAndAuthenticated());
        deliver(s, msg(AUTHENTICATION_SUCCEEDED));
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());

        // the whole script as one stream, as a phone that does not wait for answers would send it
        SimulatedPhone eager = new SimulatedPhone();
        ControlSession t = session(eager);
        deliver(t, script(challenge));
        assertTrue(SIMULATED, t.isIdentifiedAndAuthenticated());
        assertEquals(3, eager.received.size());
    }

    @Test
    public void everySplitOfEveryMessageReachesTheSameResult() throws Exception {
        byte[][] script = scriptMessages(random(32));
        for (int which = 0; which < script.length; which++) {
            for (int cut = 1; cut < script[which].length; cut++) {
                SimulatedPhone phone = new SimulatedPhone();
                ControlSession s = session(phone);
                for (int i = 0; i < script.length; i++) {
                    byte[] m = script[i];
                    if (i != which) {
                        s.receive(m, 0, m.length, now);
                        continue;
                    }
                    int before = phone.received.size();
                    s.receive(m, 0, cut, now);
                    assertEquals("nothing happens on part of a message", before, phone.received.size());
                    s.receive(m, cut, m.length - cut, now);
                }
                assertTrue(SIMULATED + "; message " + which + " cut at " + cut, s.isIdentifiedAndAuthenticated());
            }
        }
        byte[] stream = script(random(32));
        SimulatedPhone bytewise = new SimulatedPhone();
        ControlSession s = session(bytewise);
        for (int i = 0; i < stream.length; i++) s.receive(stream, i, 1, now);
        assertTrue(SIMULATED + "; byte by byte", s.isIdentifiedAndAuthenticated());
        Random chunks = new Random(3);
        for (int round = 0; round < 50; round++) {
            byte[] challenge = random(32);
            stream = script(challenge);
            SimulatedPhone phone = new SimulatedPhone();
            ControlSession r = session(phone);
            for (int off = 0; off < stream.length; ) {
                int n = Math.min(stream.length - off, 1 + chunks.nextInt(20));
                r.receive(stream, off, n, now);
                off += n;
            }
            assertTrue(SIMULATED + "; random chunks", r.isIdentifiedAndAuthenticated());
            byte[] container = SimulatedPhone.onlyParameter0(phone.received.get(1), AUTHENTICATION_CERTIFICATE);
            assertTrue(SimulatedPhone.verifies(container, challenge, SimulatedPhone.onlyParameter0(phone.last(), AUTHENTICATION_RESPONSE)));
        }
    }

    @Test
    public void invalidFramingEndsTheSession() throws Exception {
        String[] bad = {
                "4041" + "0006" + "1D00",                       // start bytes
                "4040" + "0005" + "1D00",                       // length shorter than the header
                "4040" + "0000" + "1D00",
                "4040" + "0009" + "1D00" + "000000",            // 3 bytes after the last parameter
                "4040" + "000A" + "AA02" + "0003" + "0000",     // parameter shorter than its header
                "4040" + "000A" + "AA02" + "0008" + "0000",     // parameter runs past the message
        };
        for (String hex : bad) {
            SimulatedPhone phone = new SimulatedPhone();
            ControlSession s = session(phone);
            deliver(s, LocalMfiAuthenticatorTest.hex(hex));
            assertEquals(hex, Failure.MALFORMED, s.failure());
            deliver(s, msg(START_IDENTIFICATION));
            assertTrue("a failed session stays silent", phone.received.isEmpty());
            assertEquals(Failure.MALFORMED, s.failure());
        }
    }

    @Test
    public void outOfOrderMessagesEndTheSessionWithoutSuccess() throws Exception {
        byte[] challenge = msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, random(32)));
        assertUnexpected("challenge before the certificate", 0, challenge);
        assertUnexpected("success before any signature", 0, msg(AUTHENTICATION_SUCCEEDED));
        assertUnexpected("success right after the certificate", 1, msg(REQUEST_AUTHENTICATION_CERTIFICATE), msg(AUTHENTICATION_SUCCEEDED));
        assertUnexpected("failure before the certificate", 0, msg(AUTHENTICATION_FAILED));
        assertUnexpected("certificate requested twice", 1, msg(REQUEST_AUTHENTICATION_CERTIFICATE), msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        assertUnexpected("second challenge", 2, msg(REQUEST_AUTHENTICATION_CERTIFICATE), challenge, challenge);
        assertUnexpected("acceptance before identification started", 0, msg(IDENTIFICATION_ACCEPTED));
        assertUnexpected("rejection before identification started", 0, msg(IDENTIFICATION_REJECTED));
        assertUnexpected("identification started twice", 1, msg(START_IDENTIFICATION), msg(START_IDENTIFICATION));
        assertUnexpected("accepted twice", 1, msg(START_IDENTIFICATION), msg(IDENTIFICATION_ACCEPTED), msg(IDENTIFICATION_ACCEPTED));
        assertUnexpected("unknown message before ready", 0, msg(0x4E09));
    }

    @Test
    public void rejectedIdentificationIsCorrectedAndResentAtOnce() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        TestPayload corrected = TestPayload.fixed(identificationWith("OpenAuto test 2"));
        Set<Integer>[] asked = new Set[1];
        ControlSession s = new ControlSession(phone, identity, TestPayload.correcting(IDENTIFICATION_MESSAGE, Collections.<Integer>emptySet(), ids -> {
            asked[0] = ids;
            return corrected;
        }), TIMEOUT, now);
        deliver(s, msg(START_IDENTIFICATION));
        deliver(s, msg(IDENTIFICATION_REJECTED, param(1, new byte[0])));
        assertNull("an understood rejection with a supported correction is not a failure", s.failure());
        assertEquals(Collections.singleton(1), asked[0]);
        assertArrayEquals("the correction goes out at once", msg(IDENTIFICATION_INFORMATION,
                param(0, ascii("SYNTHETIC accessory")), param(1, ascii("OpenAuto test 2"))), phone.last());
        assertEquals(Identification.INFORMATION_SENT, s.identification());
        assertSame(corrected, s.payload());
        deliver(s, msg(IDENTIFICATION_ACCEPTED));
        authenticate(s, phone);
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());
    }

    @Test
    public void identificationRejectedTooOftenEndsTheSession() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = new ControlSession(phone, identity, renaming(1), TIMEOUT, now);
        deliver(s, msg(START_IDENTIFICATION));
        for (int attempt = 1; attempt < ControlSession.MAX_IDENTIFICATION_ATTEMPTS; attempt++) {
            deliver(s, msg(IDENTIFICATION_REJECTED, param(1, new byte[0])));
            assertNull(s.failure());
        }
        deliver(s, msg(IDENTIFICATION_REJECTED, param(1, new byte[0])));
        assertEquals(Failure.IDENTIFICATION_REJECTED, s.failure());
        assertEquals(ControlSession.MAX_IDENTIFICATION_ATTEMPTS, phone.received.size());
    }

    @Test
    public void rejectionWithoutUnderstoodReasonOrSupportedCorrectionIsFinal() throws Exception {
        assertRejectionFinal("no parameter named", msg(IDENTIFICATION_REJECTED), "names no parameter");
        assertRejectionFinal("a parameter this accessory never sent", msg(IDENTIFICATION_REJECTED, param(12, new byte[0])), "did not send");
        assertRejectionFinal("one known and one unknown parameter",
                msg(IDENTIFICATION_REJECTED, param(1, new byte[0]), param(12, new byte[0])), "did not send");
        assertRejectionFinal("a mandatory parameter without a correction", msg(IDENTIFICATION_REJECTED, param(1, new byte[0])), "mandatory");
    }

    /** Corrections are compared by content: order alone is no change, and nothing but the rejected parameters may change. */
    @Test
    public void onlyCorrectionsOfExactlyTheRejectedParametersAreSent() throws Exception {
        Parameter group = new Parameter(17, CsmMessage.encodeParameters(Arrays.asList(text(0, "a"), text(1, "b"))));
        Parameter reorderedGroup = new Parameter(17, CsmMessage.encodeParameters(Arrays.asList(text(1, "b"), text(0, "a"))));
        Parameter changedGroup = new Parameter(17, CsmMessage.encodeParameters(Arrays.asList(text(0, "a"), text(1, "c"))));
        CsmMessage base = info(text(0, "name"), text(1, "model"), text(13, "de"), text(13, "en"), group);
        assertCorrection("unchanged", base, rejecting(1), base, false);
        assertCorrection("repeated parameters reordered", base, rejecting(13),
                info(text(0, "name"), text(1, "model"), text(13, "en"), text(13, "de"), group), false);
        assertCorrection("parameters reordered", base, rejecting(1),
                info(text(1, "model"), text(0, "name"), text(13, "de"), text(13, "en"), group), false);
        assertCorrection("the parts of a group reordered", base, rejecting(17),
                info(text(0, "name"), text(1, "model"), text(13, "de"), text(13, "en"), reorderedGroup), false);
        assertCorrection("an unrelated parameter changed instead", base, rejecting(1),
                info(text(0, "other"), text(1, "model"), text(13, "de"), text(13, "en"), group), false);
        assertCorrection("the rejected and an unrelated parameter changed", base, rejecting(1),
                info(text(0, "other"), text(1, "model 2"), text(13, "de"), text(13, "en"), group), false);
        assertCorrection("one of two rejected parameters left as it was", base, rejecting(0, 1),
                info(text(0, "name"), text(1, "model 2"), text(13, "de"), text(13, "en"), group), false);
        assertCorrection("the rejected parameter changed", base, rejecting(1),
                info(text(0, "name"), text(1, "model 2"), text(13, "de"), text(13, "en"), group), true);
        assertCorrection("the rejected parameter left out", base, rejecting(1),
                info(text(0, "name"), text(13, "de"), text(13, "en"), group), true);
        assertCorrection("one of the repeated values dropped", base, rejecting(13),
                info(text(0, "name"), text(1, "model"), text(13, "en"), group), true);
        assertCorrection("a part of the rejected group changed", base, rejecting(17),
                info(text(0, "name"), text(1, "model"), text(13, "de"), text(13, "en"), changedGroup), true);
        assertCorrection("a part of an unrejected group changed", base, rejecting(1),
                info(text(0, "name"), text(1, "model 2"), text(13, "de"), text(13, "en"), changedGroup), false);
    }

    @Test
    public void failureClearsProgressAndKeepsTheFirstReason() throws Exception {
        ControlSession s = session(new SimulatedPhone());
        deliver(s, script(random(32)));
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());
        s.linkLost();
        assertEquals(Failure.DISCONNECTED, s.failure());
        assertEquals(Identification.NOT_STARTED, s.identification());
        assertEquals(Authentication.NOT_STARTED, s.authentication());
        assertTrue(s.failureDetail(), s.failureDetail().contains("identification ACCEPTED, authentication SUCCEEDED"));
        s.poll(now + 10 * TIMEOUT);
        assertEquals("the first reason stays", Failure.DISCONNECTED, s.failure());
        deliver(s, msg(AUTHENTICATION_SUCCEEDED));
        assertFalse("a verdict after the end counts for nothing", s.isIdentifiedAndAuthenticated());
    }

    @Test
    public void linkLostWhileSendingKeepsThatReasonAndNoProgress() {
        ControlSession[] self = new ControlSession[1];
        self[0] = new ControlSession(message -> {
            self[0].linkLost(); // what the link layer reports before its send fails
            throw new IOException("write failed");
        }, identity, IDENTIFICATION, TIMEOUT, now);
        deliver(self[0], msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        assertEquals(Failure.DISCONNECTED, self[0].failure());
        assertEquals("the certificate never left", Authentication.NOT_STARTED, self[0].authentication());
    }

    @Test
    public void recordsWhichExchangeThePhoneStartedFirst() throws Exception {
        ControlSession identificationFirst = session(new SimulatedPhone());
        assertNull(identificationFirst.firstExchange());
        deliver(identificationFirst, script(random(32)));
        assertEquals(ControlSession.Exchange.IDENTIFICATION, identificationFirst.firstExchange());
        ControlSession authenticationFirst = session(new SimulatedPhone());
        deliver(authenticationFirst, concat(msg(REQUEST_AUTHENTICATION_CERTIFICATE), msg(START_IDENTIFICATION)));
        assertEquals(ControlSession.Exchange.AUTHENTICATION, authenticationFirst.firstExchange());
    }

    @Test
    public void authenticationFailureIsFinalAndNeverRetried() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        deliver(s, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        deliver(s, msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, random(32))));
        deliver(s, msg(AUTHENTICATION_FAILED));
        assertEquals(Failure.AUTHENTICATION_FAILED, s.failure());
        assertFalse(s.isIdentifiedAndAuthenticated());
        deliver(s, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        deliver(s, msg(START_IDENTIFICATION));
        assertEquals("nothing sent after the failure", 2, phone.received.size());

        SimulatedPhone refusesCertificate = new SimulatedPhone();
        ControlSession t = session(refusesCertificate);
        deliver(t, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        deliver(t, msg(AUTHENTICATION_FAILED));
        assertEquals(Failure.AUTHENTICATION_FAILED, t.failure());
        assertEquals("no signature was ever made", 1, refusesCertificate.received.size());
    }

    @Test
    public void challengeMustBe32BytesInExactlyOneParameter() throws Exception {
        for (int len : new int[]{0, 20, 31, 33, 64}) {
            assertChallengeFailure("challenge of " + len + " bytes", Failure.BAD_CHALLENGE, param(0, random(len)));
        }
        assertChallengeFailure("no parameters", Failure.MALFORMED);
        assertChallengeFailure("only parameter 1", Failure.MALFORMED, param(1, random(32)));
        assertChallengeFailure("parameter 0 twice", Failure.MALFORMED, param(0, random(32)), param(0, random(32)));
    }

    @Test
    public void noProgressTimesOutButAReadySessionDoesNot() throws Exception {
        ControlSession silent = session(new SimulatedPhone());
        silent.poll(now + TIMEOUT - 1);
        assertNull(silent.failure());
        silent.poll(now + TIMEOUT);
        assertEquals(Failure.TIMEOUT, silent.failure());

        ControlSession progress = session(new SimulatedPhone());
        progress.receive(msg(START_IDENTIFICATION), 0, 6, now + 4000);
        progress.poll(now + 4000 + TIMEOUT - 1);
        assertNull("each step restarts the timeout", progress.failure());
        progress.poll(now + 4000 + TIMEOUT);
        assertEquals(Failure.TIMEOUT, progress.failure());

        SimulatedPhone phone = new SimulatedPhone();
        ControlSession noVerdict = session(phone);
        deliver(noVerdict, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        deliver(noVerdict, msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, random(32))));
        noVerdict.poll(now + TIMEOUT);
        assertEquals("a signature without the phone's verdict never becomes success", Failure.TIMEOUT, noVerdict.failure());
        assertTrue(noVerdict.failureDetail(), noVerdict.failureDetail().contains("authentication RESPONSE_SENT"));
        assertEquals("cleared on failure", Authentication.NOT_STARTED, noVerdict.authentication());
        assertFalse(noVerdict.isIdentifiedAndAuthenticated());

        ControlSession corrected = new ControlSession(new SimulatedPhone(), identity, renaming(1), TIMEOUT, now);
        deliver(corrected, msg(START_IDENTIFICATION));
        deliver(corrected, msg(IDENTIFICATION_REJECTED, param(1, new byte[0])));
        corrected.poll(now + TIMEOUT);
        assertEquals("a correction without a verdict times out too", Failure.TIMEOUT, corrected.failure());

        ControlSession ready = session(new SimulatedPhone());
        deliver(ready, script(random(32)));
        ready.poll(now + 100 * TIMEOUT);
        assertTrue(SIMULATED, ready.isIdentifiedAndAuthenticated());
    }

    @Test
    public void lostLinkEndsTheSessionEvenWhenReady() throws Exception {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        byte[] start = msg(START_IDENTIFICATION);
        s.receive(start, 0, 3, now);
        s.linkLost();
        assertEquals(Failure.DISCONNECTED, s.failure());
        assertTrue(s.failureDetail(), s.failureDetail().contains("3 bytes of a message pending"));
        s.receive(start, 3, 3, now);
        assertTrue(phone.received.isEmpty());

        ControlSession ready = session(new SimulatedPhone());
        deliver(ready, script(random(32)));
        assertTrue(SIMULATED, ready.isIdentifiedAndAuthenticated());
        ready.linkLost();
        assertFalse(ready.isIdentifiedAndAuthenticated());
        assertEquals(Failure.DISCONNECTED, ready.failure());
    }

    /** Packet limits are the link's business (it splits messages); only a link that cannot send ends the session. */
    @Test
    public void linkThatCannotSendEndsTheSession() throws Exception {
        SimulatedPhone down = new SimulatedPhone();
        down.linkDown = true;
        ControlSession t = session(down);
        deliver(t, msg(START_IDENTIFICATION));
        assertEquals(Failure.LINK_ERROR, t.failure());
    }

    @Test
    public void afterReadyOtherMessagesGoToTheLayerAbove() throws Exception {
        ControlSession s = session(new SimulatedPhone());
        deliver(s, script(random(32)));
        assertTrue(SIMULATED, s.isIdentifiedAndAuthenticated());
        byte[] two = concat(msg(0x4E09, param(1, new byte[]{7})), msg(0x5000));
        List<CsmMessage> above = s.receive(two, 0, two.length, now);
        assertEquals(2, above.size());
        assertEquals(0x4E09, above.get(0).id);
        assertEquals(0x5000, above.get(1).id);
        assertTrue(s.isIdentifiedAndAuthenticated());
        deliver(s, msg(AUTHENTICATION_SUCCEEDED));
        assertEquals("handshake messages stay out of order after ready", Failure.UNEXPECTED_MESSAGE, s.failure());
        assertFalse(s.isIdentifiedAndAuthenticated());
    }

    // ---- helpers ----

    private ControlSession session(SimulatedPhone phone) {
        return new ControlSession(phone, identity, IDENTIFICATION, TIMEOUT, now);
    }

    private void deliver(ControlSession s, byte[] bytes) {
        s.receive(bytes, 0, bytes.length, now);
    }

    /** RequestAuthenticationCertificate to AuthenticationSucceeded, checked from the phone's side. */
    private void authenticate(ControlSession s, SimulatedPhone phone) throws Exception {
        deliver(s, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        byte[] container = SimulatedPhone.onlyParameter0(phone.last(), AUTHENTICATION_CERTIFICATE);
        assertArrayEquals("the certificate container goes out as loaded", identity.readCertificate(4096), container);
        assertEquals(Authentication.CERTIFICATE_SENT, s.authentication());
        byte[] challenge = random(32);
        deliver(s, msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, challenge)));
        byte[] signature = SimulatedPhone.onlyParameter0(phone.last(), AUTHENTICATION_RESPONSE);
        assertEquals(64, signature.length);
        assertTrue("signature checked with the key in the certificate the phone received", SimulatedPhone.verifies(container, challenge, signature));
        assertEquals(Authentication.RESPONSE_SENT, s.authentication());
        assertFalse("a sent signature is not success", s.isIdentifiedAndAuthenticated());
        deliver(s, msg(AUTHENTICATION_SUCCEEDED));
        assertEquals(Authentication.SUCCEEDED, s.authentication());
    }

    private void assertUnexpected(String what, int answers, byte[]... messages) {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        for (byte[] m : messages) deliver(s, m);
        assertEquals(what, Failure.UNEXPECTED_MESSAGE, s.failure());
        assertFalse(what, s.isIdentifiedAndAuthenticated());
        assertEquals(what + ": messages the accessory sent", answers, phone.received.size());
    }

    private void assertRejectionFinal(String what, byte[] rejection, String reason) {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        deliver(s, msg(START_IDENTIFICATION));
        deliver(s, rejection);
        assertEquals(what, Failure.IDENTIFICATION_REJECTED, s.failure());
        assertTrue(what + ": " + s.failureDetail(), s.failureDetail().contains(reason));
        assertEquals(what + ": nothing resent", 1, phone.received.size());
    }

    private void assertCorrection(String what, CsmMessage base, byte[] rejection, CsmMessage offered, boolean sent) {
        SimulatedPhone phone = new SimulatedPhone();
        Set<Integer> groups = Collections.singleton(17);
        ControlSession s = new ControlSession(phone, identity, TestPayload.correcting(base, groups, ids -> TestPayload.fixed(offered, groups)), TIMEOUT, now);
        deliver(s, msg(START_IDENTIFICATION));
        deliver(s, rejection);
        if (sent) {
            assertNull(what + ": " + s.failureDetail(), s.failure());
            assertEquals(what, 2, phone.received.size());
            assertArrayEquals(what, offered.encode(), phone.last());
        } else {
            assertEquals(what, Failure.IDENTIFICATION_REJECTED, s.failure());
            assertEquals(what + ": nothing resent", 1, phone.received.size());
        }
    }

    /** A payload whose every correction gives parameter 1 a new value. */
    private static TestPayload renaming(int attempt) {
        return TestPayload.correcting(identificationWith("OpenAuto test " + attempt), Collections.<Integer>emptySet(), ids -> renaming(attempt + 1));
    }

    private static CsmMessage info(Parameter... parameters) {
        return new CsmMessage(IDENTIFICATION_INFORMATION, parameters);
    }

    private static Parameter text(int id, String value) {
        return new Parameter(id, ascii(value));
    }

    private static byte[] rejecting(int... ids) {
        byte[][] params = new byte[ids.length][];
        for (int i = 0; i < ids.length; i++) params[i] = param(ids[i], new byte[0]);
        return msg(IDENTIFICATION_REJECTED, params);
    }

    private static CsmMessage identificationWith(String model) {
        return new CsmMessage(IDENTIFICATION_INFORMATION, new Parameter(0, ascii("SYNTHETIC accessory")), new Parameter(1, ascii(model)));
    }

    private void assertChallengeFailure(String what, Failure expected, byte[]... params) {
        SimulatedPhone phone = new SimulatedPhone();
        ControlSession s = session(phone);
        deliver(s, msg(REQUEST_AUTHENTICATION_CERTIFICATE));
        deliver(s, msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, params));
        assertEquals(what, expected, s.failure());
        assertEquals(what + ": no signature sent", 1, phone.received.size());
    }

    private static byte[][] scriptMessages(byte[] challenge) {
        return new byte[][]{msg(START_IDENTIFICATION), msg(IDENTIFICATION_ACCEPTED), msg(REQUEST_AUTHENTICATION_CERTIFICATE),
                msg(REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, param(0, challenge)), msg(AUTHENTICATION_SUCCEEDED)};
    }

    private static byte[] script(byte[] challenge) {
        return concat(scriptMessages(challenge));
    }

    /** A phone message written byte by byte: 0x4040, length, ID, then the encoded parameters. */
    static byte[] msg(int id, byte[]... params) {
        byte[] body = concat(params);
        return concat(new byte[]{0x40, 0x40, (byte) ((6 + body.length) >> 8), (byte) (6 + body.length), (byte) (id >> 8), (byte) id}, body);
    }

    static byte[] param(int id, byte[] value) {
        int len = 4 + value.length;
        return concat(new byte[]{(byte) (len >> 8), (byte) len, (byte) (id >> 8), (byte) id}, value);
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** An identification payload for tests: a fixed message and a scripted correction. */
    static final class TestPayload implements IdentificationPayload {
        interface Corrector {
            IdentificationPayload correct(Set<Integer> rejected) throws Uncorrectable;
        }

        private final CsmMessage message;
        private final Set<Integer> groups;
        private final Corrector corrector;

        private TestPayload(CsmMessage message, Set<Integer> groups, Corrector corrector) {
            this.message = message;
            this.groups = groups;
            this.corrector = corrector;
        }

        static TestPayload fixed(CsmMessage message) {
            return fixed(message, Collections.<Integer>emptySet());
        }

        static TestPayload fixed(CsmMessage message, Set<Integer> groups) {
            return new TestPayload(message, groups, ids -> {
                throw new Uncorrectable("parameters " + ids + " are mandatory in this test payload");
            });
        }

        static TestPayload correcting(CsmMessage message, Set<Integer> groups, Corrector corrector) {
            return new TestPayload(message, groups, corrector);
        }

        @Override
        public CsmMessage message() {
            return message;
        }

        @Override
        public Set<Integer> groupIds() {
            return groups;
        }

        @Override
        public IdentificationPayload correctedFor(Set<Integer> rejectedIds) throws Uncorrectable {
            return corrector.correct(rejectedIds);
        }
    }

    /**
     * The phone's side for tests, above the link: records the whole messages the accessory sends,
     * parses them with its own code and verifies signatures with the host JCA.
     */
    static final class SimulatedPhone implements Iap2Link {
        final List<byte[]> received = new ArrayList<>();
        boolean linkDown;

        @Override
        public void sendControl(byte[] message) throws IOException {
            if (linkDown) throw new IOException("link down");
            received.add(message.clone());
        }

        byte[] last() {
            return received.get(received.size() - 1);
        }

        /** The value of the single parameter (ID 0) of a message with the expected ID. */
        static byte[] onlyParameter0(byte[] m, int expectedId) {
            assertEquals(0x4040, u16(m, 0));
            assertEquals("length field", m.length, u16(m, 2));
            assertEquals(String.format("message ID of %s", Arrays.toString(Arrays.copyOf(m, 6))), expectedId, u16(m, 4));
            assertEquals("one parameter filling the message", m.length - 6, u16(m, 6));
            assertEquals("parameter ID", 0, u16(m, 8));
            return Arrays.copyOfRange(m, 10, m.length);
        }

        static boolean verifies(byte[] certificateContainer, byte[] challenge, byte[] rawSignature) throws Exception {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(certificateContainer));
            assertEquals(1, certs.size());
            Signature v = Signature.getInstance("NONEwithECDSAinP1363Format");
            v.initVerify(certs.iterator().next().getPublicKey());
            v.update(challenge);
            return v.verify(rawSignature);
        }

        private static int u16(byte[] b, int off) {
            return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
        }
    }
}
