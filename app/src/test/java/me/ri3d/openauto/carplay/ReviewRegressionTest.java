package me.ri3d.openauto.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.IdentityException;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.Reason;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest;
import me.ri3d.openauto.carplay.iap2.BluetoothProfile;
import me.ri3d.openauto.carplay.iap2.ControlSession;
import me.ri3d.openauto.carplay.iap2.CsmMessage;
import me.ri3d.openauto.carplay.iap2.Iap2Connection;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer;

/**
 * Regressions from an independent review: P-256 parameters that differ only in the cofactor, and
 * a failed session of an earlier link closing the next link while it is still being set up.
 * Synthetic identities and a scripted phone only.
 */
public class ReviewRegressionTest {
    private static final ECDomainParameters P256 = new ECDomainParameters(SECNamedCurves.getByName("secp256r1"));
    private static final byte[] ANSWER = new Iap2LinkLayer.Parameters(1, 2, 64, 800, 10, 3, 1,
            new Iap2LinkLayer.SessionEntry(BluetoothProfile.CONTROL_SESSION_ID, 0, BluetoothProfile.CONTROL_SESSION_VERSION)).encode();
    private static final int SYN = Iap2LinkLayer.SYN, ACK = Iap2LinkLayer.ACK, RST = Iap2LinkLayer.RST;

    @ClassRule
    public static final TemporaryFolder TMP = new TemporaryFolder();
    private static KeyPair holder;
    private static LocalMfiAuthenticator identity;

    private final List<byte[]> out = new ArrayList<>();
    private long now = 1_000;

    @BeforeClass
    public static void syntheticIdentity() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        holder = g.generateKeyPair();
        identity = LocalMfiAuthenticator.load(dir(holder.getPrivate().getEncoded(), LocalMfiAuthenticatorTest.certificate(holder.getPublic().getEncoded())));
    }

    // ---- P-256 cofactor ----

    @Test
    public void cofactorOtherThanOneIsRefusedForKeyAndCertificate() throws Exception {
        ECDomainParameters h2 = new ECDomainParameters(P256.getCurve(), P256.getG(), P256.getN(), BigInteger.valueOf(2), null);
        BigInteger d = ((ECPrivateKey) holder.getPrivate()).getS();
        byte[] namedKey = holder.getPrivate().getEncoded(), namedSpki = holder.getPublic().getEncoded();
        assertUnsupported("key and certificate with cofactor 2", pkcs8(h2, d), spki(h2, d));
        assertUnsupported("certificate with cofactor 2", namedKey, spki(h2, d));
        assertUnsupported("key with cofactor 2", pkcs8(h2, d), namedSpki);
    }

    @Test
    public void namedAndExplicitP256StillLoad() throws Exception {
        BigInteger d = ((ECPrivateKey) holder.getPrivate()).getS();
        LocalMfiAuthenticator.load(dir(holder.getPrivate().getEncoded(), LocalMfiAuthenticatorTest.certificate(holder.getPublic().getEncoded())));
        LocalMfiAuthenticator.load(dir(pkcs8(P256, d), LocalMfiAuthenticatorTest.certificate(spki(P256, d))));
        ECDomainParameters noCofactor = new ECDomainParameters(P256.getCurve(), P256.getG(), P256.getN(), null, null);
        LocalMfiAuthenticator.load(dir(pkcs8(noCofactor, d), LocalMfiAuthenticatorTest.certificate(spki(noCofactor, d)))); // optional in X9.62
    }

    // ---- reconnect after a failed session ----

    @Test
    public void failedSessionDoesNotCloseTheNextLinkDuringDetection() throws Exception {
        Iap2Connection c = connection(BluetoothProfile.DETECT);
        c.start(now, 100);
        echoDetection(c);
        synchronize(c, 40);
        ControlSession first = failSession(c, 41);

        c.start(now, 120);
        c.poll(now);
        echoDetection(c);
        assertEquals("still setting up, not closed by the old session", Iap2LinkLayer.State.SYNCHRONIZING, c.link().state());
        synchronize(c, 70);
        assertFresh(c, first);
    }

    @Test
    public void failedSessionDoesNotCloseTheNextLinkDuringSynchronization() throws Exception {
        Iap2Connection c = connection(null);
        c.start(now, 100);
        synchronize(c, 40);
        ControlSession first = failSession(c, 41);

        c.start(now, 120);
        c.poll(now);
        byte[] stray = Iap2LinkLayer.packet(ACK, 42, 100, BluetoothProfile.CONTROL_SESSION_ID, new byte[]{1});
        c.receive(stray, 0, stray.length, now);
        assertEquals(Iap2LinkLayer.State.SYNCHRONIZING, c.link().state());
        synchronize(c, 70);
        assertFresh(c, first);
    }

    @Test
    public void sessionEndedByResetDoesNotCloseTheNextLink() throws Exception {
        Iap2Connection c = connection(null);
        c.start(now, 100);
        synchronize(c, 40);
        ControlSession first = c.session();
        byte[] rst = Iap2LinkLayer.packet(RST, 41, 100, 0, new byte[0]);
        c.receive(rst, 0, rst.length, now);
        assertEquals(ControlSession.Failure.DISCONNECTED, first.failure());

        c.start(now, 120);
        for (int i = 0; i < 3; i++) c.poll(now + 5L * i);
        assertEquals(Iap2LinkLayer.State.SYNCHRONIZING, c.link().state());
        synchronize(c, 70);
        assertFresh(c, first);
    }

    // ---- helpers ----

    private Iap2Connection connection(byte[] detect) {
        BluetoothProfile.Identification payload = new BluetoothProfile.Identification("SYNTHETIC", "MO", "MA", "SN", "FW", "HW",
                new byte[]{0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F});
        return new Iap2Connection(out::add, BluetoothProfile.linkProposal(), detect, identity, payload, 10_000);
    }

    private void echoDetection(Iap2Connection c) {
        byte[] detect = out.get(out.size() - 1);
        c.receive(detect, 0, detect.length, now);
    }

    private void synchronize(Iap2Connection c, int phoneSeq) {
        byte[] syn = out.get(out.size() - 1);
        assertEquals("a SYN is the last thing sent", SYN, syn[4] & 0xFF);
        byte[] answer = Iap2LinkLayer.packet(SYN | ACK, phoneSeq, syn[5] & 0xFF, 0, ANSWER);
        c.receive(answer, 0, answer.length, now);
        assertEquals(Iap2LinkLayer.State.UP, c.link().state());
    }

    /** An unsolicited AuthenticationSucceeded fails the session, which closes its link. */
    private ControlSession failSession(Iap2Connection c, int phoneSeq) {
        ControlSession s = c.session();
        byte[] verdict = Iap2LinkLayer.packet(ACK, phoneSeq, 0, BluetoothProfile.CONTROL_SESSION_ID,
                new CsmMessage(ControlSession.AUTHENTICATION_SUCCEEDED).encode());
        c.receive(verdict, 0, verdict.length, now);
        assertEquals(ControlSession.Failure.UNEXPECTED_MESSAGE, s.failure());
        assertEquals(Iap2LinkLayer.Down.CLOSED, c.link().downReason());
        return s;
    }

    private static void assertFresh(Iap2Connection c, ControlSession previous) {
        assertNotSame("a new session for the new link", previous, c.session());
        assertNull(c.session().failure());
        assertEquals(ControlSession.Identification.NOT_STARTED, c.session().identification());
        assertEquals(ControlSession.Authentication.NOT_STARTED, c.session().authentication());
        assertEquals(Iap2LinkLayer.State.UP, c.link().state());
    }

    private static void assertUnsupported(String what, byte[] key, byte[] spki) throws Exception {
        try {
            LocalMfiAuthenticator.load(dir(key, LocalMfiAuthenticatorTest.certificate(spki)));
            fail(what + ": loaded");
        } catch (IdentityException e) {
            assertEquals(what + ": " + e.getMessage(), Reason.UNSUPPORTED, e.reason);
        }
    }

    private static byte[] pkcs8(ECDomainParameters curve, BigInteger d) throws IOException {
        return PrivateKeyInfoFactory.createPrivateKeyInfo(new ECPrivateKeyParameters(d, curve)).getEncoded(ASN1Encoding.DER);
    }

    private static byte[] spki(ECDomainParameters curve, BigInteger d) throws IOException {
        return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(new ECPublicKeyParameters(curve.getG().multiply(d).normalize(), curve))
                .getEncoded(ASN1Encoding.DER);
    }

    private static File dir(byte[] key, byte[] certificate) throws IOException {
        File d = TMP.newFolder();
        Files.write(new File(d, LocalMfiAuthenticator.KEY_FILE).toPath(), key);
        Files.write(new File(d, LocalMfiAuthenticator.CERTIFICATE_FILE).toPath(), certificate);
        return d;
    }
}
