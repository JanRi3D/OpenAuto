package me.ri3d.openauto.carplay.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECNamedDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.IdentityException;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.Reason;

/**
 * Identity loading and challenge signing with synthetic identities only. Keys and PKCS#8 encodings
 * come from the host JCA and signatures are checked with it, so the code under test never grades
 * its own output.
 */
public class LocalMfiAuthenticatorTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final KeyPair CA = ecKeyPair("secp256r1");
    private static final KeyPair HOLDER = ecKeyPair("secp256r1");
    private static final int[] BAD_DIGEST_LENGTHS = {0, 20, 31, 33, 64};

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void loadsDerCertificateAndSigns() throws Exception {
        byte[] cert = certificate(HOLDER.getPublic().getEncoded());
        LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(identity(HOLDER.getPrivate().getEncoded(), cert));
        assertEquals(3, auth.protocolMajor());
        assertArrayEquals(cert, auth.readCertificate(cert.length));
        byte[] digest = randomDigest();
        byte[] sig = auth.signDigest(digest);
        assertEquals(64, sig.length);
        assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, sig));
        assertTrue(verifies("NONEwithECDSA", HOLDER.getPublic(), digest, RawEcdsaSignature.toDer(sig)));
    }

    @Test
    public void loadsOneCertificatePkcs7EnvelopeAndKeepsItsBytes() throws Exception {
        byte[] cert = certificate(HOLDER.getPublic().getEncoded());
        byte[] p7b = pkcs7(cert);
        // A real envelope, as the host's own PKCS#7 parser sees it, not a renamed certificate.
        assertFalse(Arrays.equals(cert, p7b));
        assertEquals(1, CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(p7b)).size());
        assertEquals(PKCSObjectIdentifiers.signedData, ContentInfo.getInstance(p7b).getContentType());

        LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(identity(HOLDER.getPrivate().getEncoded(), p7b));
        assertArrayEquals("the envelope is sent as loaded", p7b, auth.readCertificate(p7b.length));
        byte[] digest = randomDigest();
        assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, auth.signDigest(digest)));
    }

    @Test
    public void acceptsExplicitlyEncodedP256() throws Exception {
        ECDomainParameters explicit = new ECDomainParameters(SECNamedCurves.getByName("secp256r1")); // no OID, generic curve code
        BigInteger d = ((ECPrivateKey) HOLDER.getPrivate()).getS();
        File dir = identity(bcPkcs8(explicit, d), certificate(bcSpki(explicit, d)));
        byte[] digest = randomDigest();
        assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, LocalMfiAuthenticator.load(dir).signDigest(digest)));
    }

    @Test
    public void rejectsCurvesOtherThanP256() throws Exception {
        KeyPair p384 = ecKeyPair("secp384r1");
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(p384.getPrivate().getEncoded(), certificate(p384.getPublic().getEncoded()))));

        // 256-bit curves that only a comparison of the domain parameters tells apart from P-256
        ECDomainParameters[] others = {
                new ECNamedDomainParameters(SECObjectIdentifiers.secp256k1, SECNamedCurves.getByName("secp256k1")),
                new ECDomainParameters(SECNamedCurves.getByName("secp256k1")),
                new ECDomainParameters(TeleTrusTNamedCurves.getByName("brainpoolP256r1"))};
        for (ECDomainParameters curve : others) {
            BigInteger d = new BigInteger(255, RANDOM).add(BigInteger.ONE);
            assertEquals(Reason.UNSUPPORTED, loadFailure(identity(bcPkcs8(curve, d), certificate(bcSpki(curve, d)))));
            assertEquals("certificate key on another curve", Reason.UNSUPPORTED,
                    loadFailure(identity(HOLDER.getPrivate().getEncoded(), certificate(bcSpki(curve, d)))));
        }
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(HOLDER.getPrivate().getEncoded(), certificate(p384.getPublic().getEncoded()))));
    }

    @Test
    public void rejectsRsaMaterial() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, RANDOM);
        KeyPair rsa = g.generateKeyPair();
        byte[] rsaCert = certificate(rsa.getPublic().getEncoded());
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(rsa.getPrivate().getEncoded(), rsaCert)));
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(HOLDER.getPrivate().getEncoded(), rsaCert)));
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(rsa.getPrivate().getEncoded(), certificate(HOLDER.getPublic().getEncoded()))));
    }

    @Test
    public void rejectsKeyThatDoesNotBelongToTheCertificate() throws Exception {
        byte[] otherCert = certificate(CA.getPublic().getEncoded());
        assertEquals(Reason.MISMATCHED, loadFailure(identity(HOLDER.getPrivate().getEncoded(), otherCert)));
        assertEquals(Reason.MISMATCHED, loadFailure(identity(HOLDER.getPrivate().getEncoded(), pkcs7(otherCert))));
    }

    @Test
    public void rejectsEnvelopesWithoutExactlyOneCertificate() throws Exception {
        byte[] key = HOLDER.getPrivate().getEncoded(), cert = certificate(HOLDER.getPublic().getEncoded());
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(key, pkcs7())));
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(key, pkcs7(cert, certificate(CA.getPublic().getEncoded())))));
        SignedData noCertificates = new SignedData(new ASN1Integer(1), new DERSet(), new ContentInfo(PKCSObjectIdentifiers.data, null), null, null, new DERSet());
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(key, new ContentInfo(PKCSObjectIdentifiers.signedData, noCertificates).getEncoded(ASN1Encoding.DER))));
        byte[] notSignedData = new ContentInfo(PKCSObjectIdentifiers.data, new DEROctetString(cert)).getEncoded(ASN1Encoding.DER);
        assertEquals(Reason.UNSUPPORTED, loadFailure(identity(key, notSignedData)));
    }

    @Test
    public void rejectsMissingEmptyTruncatedAndOversizedFiles() throws Exception {
        byte[] key = HOLDER.getPrivate().getEncoded(), cert = certificate(HOLDER.getPublic().getEncoded()), p7b = pkcs7(cert);
        assertEquals(Reason.UNREADABLE, loadFailure(new File(tmp.getRoot(), "no-such-dir")));
        assertEquals("no fallback to the working directory", Reason.UNREADABLE, loadFailure(null));
        File keyOnly = identity(key, cert);
        assertTrue(new File(keyOnly, LocalMfiAuthenticator.CERTIFICATE_FILE).delete());
        assertEquals(Reason.UNREADABLE, loadFailure(keyOnly));
        File certOnly = identity(key, cert);
        assertTrue(new File(certOnly, LocalMfiAuthenticator.KEY_FILE).delete());
        assertEquals(Reason.UNREADABLE, loadFailure(certOnly));
        File dirAsFile = identity(key, cert);
        File certFile = new File(dirAsFile, LocalMfiAuthenticator.CERTIFICATE_FILE);
        assertTrue(certFile.delete() && certFile.mkdir());
        assertEquals(Reason.UNREADABLE, loadFailure(dirAsFile));

        assertEquals(Reason.LENGTH, loadFailure(identity(new byte[0], cert)));
        assertEquals(Reason.LENGTH, loadFailure(identity(key, new byte[0])));
        int max = LocalMfiAuthenticator.MAX_FILE_LENGTH;
        assertEquals(Reason.LENGTH, loadFailure(identity(key, Arrays.copyOf(cert, max + 1))));
        assertEquals(Reason.LENGTH, loadFailure(identity(Arrays.copyOf(key, max + 1), cert)));
        assertEquals("16 KiB itself is read, then parsed", Reason.MALFORMED, loadFailure(identity(key, Arrays.copyOf(cert, max))));

        assertEquals(Reason.MALFORMED, loadFailure(identity(Arrays.copyOf(key, key.length - 1), cert)));
        assertEquals(Reason.MALFORMED, loadFailure(identity(key, Arrays.copyOf(cert, cert.length - 1))));
        assertEquals(Reason.MALFORMED, loadFailure(identity(key, Arrays.copyOf(p7b, p7b.length - 1))));
        assertEquals(Reason.MALFORMED, loadFailure(identity(key, Arrays.copyOf(p7b, 40))));
        assertEquals("trailing data", Reason.MALFORMED, loadFailure(identity(key, Arrays.copyOf(cert, cert.length + 1))));
        assertEquals("trailing data", Reason.MALFORMED, loadFailure(identity(Arrays.copyOf(key, key.length + 1), cert)));
        assertEquals(Reason.MALFORMED, loadFailure(identity("not a key".getBytes(StandardCharsets.US_ASCII), cert)));
        byte[] encrypted = new EncryptedPrivateKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2), new byte[48]).getEncoded(ASN1Encoding.DER);
        assertEquals(Reason.MALFORMED, loadFailure(identity(encrypted, cert)));
    }

    @Test
    public void digestMustBeExactly32Bytes() throws Exception {
        LocalMfiAuthenticator auth = holder();
        auth.signDigest(new byte[32]);
        for (int len : BAD_DIGEST_LENGTHS) {
            try {
                auth.signDigest(new byte[len]);
                fail("digest of " + len + " bytes signed");
            } catch (IllegalArgumentException expected) {
                // the documented failure for a wrong input length
            }
        }
        try {
            auth.signDigest(null);
            fail("null digest signed");
        } catch (IllegalArgumentException expected) {
            // same
        }
    }

    @Test
    public void signsTheDigestWithoutHashingItAgain() throws Exception {
        byte[] digest = randomDigest();
        byte[] sig = holder().signDigest(digest);
        assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, sig));
        // A signer that hashed the digest again would produce exactly what this accepts.
        assertFalse(verifies("SHA256withECDSAinP1363Format", HOLDER.getPublic(), digest, sig));
    }

    @Test
    public void rejectsAlteredDigestKeyOrSignature() throws Exception {
        byte[] digest = randomDigest();
        byte[] sig = holder().signDigest(digest);
        byte[] otherDigest = digest.clone();
        otherDigest[31] ^= 1;
        assertFalse(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), otherDigest, sig));
        assertFalse(verifies("NONEwithECDSAinP1363Format", CA.getPublic(), digest, sig));
        for (int i : new int[]{0, 31, 32, 63}) {
            byte[] otherSig = sig.clone();
            otherSig[i] ^= 0x10;
            assertFalse("bit flipped in byte " + i, verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, otherSig));
        }
    }

    /** RFC 6979 A.2.5: P-256 with SHA-256, messages "sample" and "test"; the public key is the RFC's, not derived here. */
    @Test
    public void matchesRfc6979KnownAnswers() throws Exception {
        ECParameterSpec p256 = p256Spec();
        KeyFactory kf = KeyFactory.getInstance("EC");
        byte[] key = kf.generatePrivate(new ECPrivateKeySpec(hexInt("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721"), p256)).getEncoded();
        PublicKey pub = kf.generatePublic(new ECPublicKeySpec(new ECPoint(
                hexInt("60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6"),
                hexInt("7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299")), p256));
        LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(identity(key, certificate(pub.getEncoded())));
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        assertArrayEquals(hex("EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716"
                        + "F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8"),
                auth.signDigest(sha256.digest("sample".getBytes(StandardCharsets.US_ASCII))));
        assertArrayEquals(hex("F1ABB023518351CD71D881567B1EA663ED3EFCF6C5132B354F28D3B0B7D38367"
                        + "019F4113742A2B14BD25926B49C649155F267E60D3814B4C0CC84250E46F0083"),
                auth.signDigest(sha256.digest("test".getBytes(StandardCharsets.US_ASCII))));
    }

    /** Signatures made by the host JCA, converted from DER, verify as raw; ours, converted to DER, verify in the JCA. */
    @Test
    public void interoperatesWithHostJcaSignatures() throws Exception {
        LocalMfiAuthenticator auth = holder();
        Signature jca = Signature.getInstance("NONEwithECDSA");
        for (int i = 0; i < 200; i++) { // enough to meet short r/s and both sign-padding cases
            byte[] digest = randomDigest();
            jca.initSign(HOLDER.getPrivate(), RANDOM);
            jca.update(digest);
            byte[] raw = RawEcdsaSignature.fromDer(jca.sign());
            assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digest, raw));
            assertTrue(verifies("NONEwithECDSA", HOLDER.getPublic(), digest, RawEcdsaSignature.toDer(auth.signDigest(digest))));
        }
    }

    @Test
    public void certificateCopiesAreIndependentAndBounded() throws Exception {
        byte[] p7b = pkcs7(certificate(HOLDER.getPublic().getEncoded()));
        LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(identity(HOLDER.getPrivate().getEncoded(), p7b));
        byte[] a = auth.readCertificate(Integer.MAX_VALUE), b = auth.readCertificate(p7b.length);
        assertNotSame(a, b);
        a[0] ^= 1;
        assertArrayEquals(p7b, b);
        assertArrayEquals(p7b, auth.readCertificate(p7b.length));
        for (int limit : new int[]{p7b.length - 1, 0, -1}) {
            try {
                auth.readCertificate(limit);
                fail("certificate returned with limit " + limit);
            } catch (IllegalArgumentException expected) {
                // limit below the certificate's length
            }
        }
    }

    @Test
    public void reloadGivesTheSameIdentity() throws Exception {
        File dir = identity(HOLDER.getPrivate().getEncoded(), pkcs7(certificate(HOLDER.getPublic().getEncoded())));
        LocalMfiAuthenticator first = LocalMfiAuthenticator.load(dir), second = LocalMfiAuthenticator.load(dir);
        assertArrayEquals(first.readCertificate(4096), second.readCertificate(4096));
        byte[] digest = randomDigest();
        assertArrayEquals("RFC 6979 signatures are deterministic", first.signDigest(digest), second.signDigest(digest));
    }

    @Test
    public void oneInstanceSignsConcurrently() throws Exception {
        final LocalMfiAuthenticator auth = holder();
        final byte[][] digests = new byte[400][];
        byte[][] expected = new byte[digests.length][];
        for (int i = 0; i < digests.length; i++) {
            digests[i] = randomDigest();
            expected[i] = auth.signDigest(digests[i]);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<byte[]>> results = new ArrayList<>();
            for (final byte[] digest : digests) results.add(pool.submit(() -> auth.signDigest(digest)));
            for (int i = 0; i < digests.length; i++) assertArrayEquals("digest " + i, expected[i], results.get(i).get());
        } finally {
            pool.shutdownNow();
        }
        assertTrue(verifies("NONEwithECDSAinP1363Format", HOLDER.getPublic(), digests[7], expected[7]));
    }

    // ---- synthetic fixtures ----

    private LocalMfiAuthenticator holder() throws Exception {
        return LocalMfiAuthenticator.load(identity(HOLDER.getPrivate().getEncoded(), certificate(HOLDER.getPublic().getEncoded())));
    }

    private File identity(byte[] keyFile, byte[] certificateFile) throws IOException {
        File dir = tmp.newFolder();
        Files.write(new File(dir, LocalMfiAuthenticator.KEY_FILE).toPath(), keyFile);
        Files.write(new File(dir, LocalMfiAuthenticator.CERTIFICATE_FILE).toPath(), certificateFile);
        return dir;
    }

    private static Reason loadFailure(File dir) {
        try {
            LocalMfiAuthenticator.load(dir);
            throw new AssertionError("identity in " + dir + " loaded although it is invalid");
        } catch (IdentityException e) {
            return e.reason;
        }
    }

    private static KeyPair ecKeyPair(String curve) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec(curve), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECParameterSpec p256Spec() throws GeneralSecurityException {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    /** PKCS#8 for curves the host JCA does not offer; plain ECDomainParameters are written out explicitly, without an OID. */
    private static byte[] bcPkcs8(ECDomainParameters curve, BigInteger d) throws IOException {
        return PrivateKeyInfoFactory.createPrivateKeyInfo(new ECPrivateKeyParameters(d, curve)).getEncoded(ASN1Encoding.DER);
    }

    private static byte[] bcSpki(ECDomainParameters curve, BigInteger d) throws IOException {
        ECPublicKeyParameters pub = new ECPublicKeyParameters(curve.getG().multiply(d).normalize(), curve);
        return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(pub).getEncoded(ASN1Encoding.DER);
    }

    /** An X.509 certificate for {@code spki}, issued by the synthetic CA: SHA256withECDSA over the TBS bytes. */
    public static byte[] certificate(byte[] spki) throws Exception {
        AlgorithmIdentifier alg = new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
        V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
        tbs.setSerialNumber(new ASN1Integer(new BigInteger(64, RANDOM).add(BigInteger.ONE)));
        tbs.setSignature(alg);
        tbs.setIssuer(new X500Name("CN=Synthetic test CA"));
        tbs.setSubject(new X500Name("CN=Synthetic test accessory"));
        tbs.setStartDate(new Time(new Date(0)));
        tbs.setEndDate(new Time(new Date(2524608000000L))); // 2050
        tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(spki));
        byte[] tbsDer = tbs.generateTBSCertificate().getEncoded(ASN1Encoding.DER);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(CA.getPrivate(), RANDOM);
        s.update(tbsDer);
        return new DERSequence(new ASN1Encodable[]{ASN1Primitive.fromByteArray(tbsDer), alg, new DERBitString(s.sign())}).getEncoded(ASN1Encoding.DER);
    }

    /** A certificates-only PKCS#7 SignedData envelope. */
    public static byte[] pkcs7(byte[]... certificates) throws IOException {
        ASN1EncodableVector v = new ASN1EncodableVector();
        for (byte[] c : certificates) v.add(ASN1Primitive.fromByteArray(c));
        SignedData sd = new SignedData(new ASN1Integer(1), new DERSet(), new ContentInfo(PKCSObjectIdentifiers.data, null), new DERSet(v), null, new DERSet());
        return new ContentInfo(PKCSObjectIdentifiers.signedData, sd).getEncoded(ASN1Encoding.DER);
    }

    private static byte[] randomDigest() {
        byte[] d = new byte[32];
        RANDOM.nextBytes(d);
        return d;
    }

    static boolean verifies(String algorithm, PublicKey key, byte[] message, byte[] signature) throws GeneralSecurityException {
        Signature v = Signature.getInstance(algorithm);
        v.initVerify(key);
        v.update(message);
        try {
            return v.verify(signature);
        } catch (SignatureException e) {
            return false;
        }
    }

    private static BigInteger hexInt(String s) {
        return new BigInteger(s, 16);
    }

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
