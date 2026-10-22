package me.ri3d.openauto.carplay.auth;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Accessory authentication from an identity kept in a directory on this device: an unencrypted
 * PKCS#8 P-256 private key ({@code identity.pk8}) and the accessory certificate
 * ({@code certificate.p7b}, a DER X.509 certificate or a PKCS#7 SignedData envelope holding exactly
 * one; the content decides, not the extension). The certificate container is sent byte for byte as
 * loaded. Loading only checks that the key belongs to the certificate; whether a phone trusts that
 * certificate is decided by the phone.
 *
 * <p>Uses BouncyCastle's lightweight API (no JCE provider is registered). Immutable after
 * {@link #load}; one instance may sign from several threads.
 */
public final class LocalMfiAuthenticator implements MfiAuthenticator {
    public static final String KEY_FILE = "identity.pk8";
    public static final String CERTIFICATE_FILE = "certificate.p7b";
    public static final int MAX_FILE_LENGTH = 16 * 1024;
    public static final int DIGEST_LENGTH = 32;

    static final ECDomainParameters P256 = new ECDomainParameters(CustomNamedCurves.getByName("secp256r1"));

    private final byte[] certificate;
    private final ECPrivateKeyParameters key;

    private LocalMfiAuthenticator(byte[] certificate, ECPrivateKeyParameters key) {
        this.certificate = certificate;
        this.key = key;
    }

    /** Loads and checks the identity in {@code identityDir}. Never falls back to another identity. */
    public static LocalMfiAuthenticator load(File identityDir) throws IdentityException {
        // new File(null, name) would quietly resolve against the working directory
        if (identityDir == null) throw new IdentityException(Reason.UNREADABLE, "no identity directory given");
        byte[] pkcs8 = read(new File(identityDir, KEY_FILE));
        try {
            byte[] certificate = read(new File(identityDir, CERTIFICATE_FILE));
            ECPrivateKeyParameters key = privateKey(pkcs8);
            ECPublicKeyParameters certified = certificateKey(certificate);
            if (!P256.getG().multiply(key.getD()).equals(certified.getQ())) {
                throw new IdentityException(Reason.MISMATCHED, KEY_FILE + " does not belong to the certificate in " + CERTIFICATE_FILE);
            }
            return new LocalMfiAuthenticator(certificate, key);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    @Override
    public int protocolMajor() {
        return 3;
    }

    @Override
    public byte[] readCertificate(int maximumOutputLength) {
        if (maximumOutputLength < certificate.length) {
            throw new IllegalArgumentException("certificate is " + certificate.length + " bytes, limit " + maximumOutputLength);
        }
        return certificate.clone();
    }

    @Override
    public byte[] signDigest(byte[] digest) throws GeneralSecurityException {
        if (digest == null || digest.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("digest must be " + DIGEST_LENGTH + " bytes, got " + (digest == null ? "null" : digest.length));
        }
        // ECDSASigner takes the digest as the value to sign without hashing it; SHA-256 here only
        // derives the RFC 6979 nonce. A new signer per call keeps this instance free of shared state.
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, key);
        BigInteger[] rs = signer.generateSignature(digest);
        return RawEcdsaSignature.toRaw(rs[0], rs[1]);
    }

    private static ECPrivateKeyParameters privateKey(byte[] der) throws IdentityException {
        PrivateKeyInfo info;
        try {
            info = PrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(der));
        } catch (IOException | RuntimeException e) { // no cause attached: parser messages may quote key bytes
            throw new IdentityException(Reason.MALFORMED, KEY_FILE + " is not an unencrypted PKCS#8 key");
        }
        requireEcP256(info.getPrivateKeyAlgorithm(), KEY_FILE);
        ECPrivateKeyParameters key;
        try {
            key = (ECPrivateKeyParameters) PrivateKeyFactory.createKey(info);
        } catch (IOException | RuntimeException e) {
            throw new IdentityException(Reason.MALFORMED, KEY_FILE + " holds an unreadable EC key");
        }
        requireP256(key, KEY_FILE);
        return new ECPrivateKeyParameters(key.getD(), P256); // explicitly encoded parameters get the fast curve too
    }

    private static ECPublicKeyParameters certificateKey(byte[] container) throws IdentityException {
        Certificate cert = certificate(container);
        requireEcP256(cert.getSubjectPublicKeyInfo().getAlgorithm(), CERTIFICATE_FILE);
        ECPublicKeyParameters key;
        try {
            key = (ECPublicKeyParameters) PublicKeyFactory.createKey(cert.getSubjectPublicKeyInfo());
        } catch (IOException | RuntimeException e) {
            throw new IdentityException(Reason.MALFORMED, CERTIFICATE_FILE + " holds an unreadable EC public key", e);
        }
        requireP256(key, CERTIFICATE_FILE);
        return key;
    }

    /** The one X.509 certificate in a DER certificate or in a PKCS#7 SignedData envelope. */
    private static Certificate certificate(byte[] container) throws IdentityException {
        try {
            ASN1Sequence top = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(container));
            if (!(top.getObjectAt(0) instanceof ASN1ObjectIdentifier)) return Certificate.getInstance(top);
            ContentInfo info = ContentInfo.getInstance(top);
            if (!PKCSObjectIdentifiers.signedData.equals(info.getContentType())) {
                throw new IdentityException(Reason.UNSUPPORTED, CERTIFICATE_FILE + ": PKCS#7 content " + info.getContentType() + " is not SignedData");
            }
            ASN1Set certs = SignedData.getInstance(info.getContent()).getCertificates();
            int count = certs == null ? 0 : certs.size();
            if (count != 1) {
                throw new IdentityException(Reason.UNSUPPORTED, CERTIFICATE_FILE + ": PKCS#7 envelope holds " + count + " certificates, expected exactly one");
            }
            ASN1Encodable only = certs.getObjectAt(0);
            if (!(only instanceof ASN1Sequence)) {
                throw new IdentityException(Reason.UNSUPPORTED, CERTIFICATE_FILE + ": the PKCS#7 entry is not an X.509 certificate");
            }
            return Certificate.getInstance(only);
        } catch (IOException | RuntimeException e) {
            throw new IdentityException(Reason.MALFORMED, CERTIFICATE_FILE + " is neither a DER X.509 certificate nor a PKCS#7 envelope", e);
        }
    }

    private static void requireEcP256(AlgorithmIdentifier alg, String file) throws IdentityException {
        if (!X9ObjectIdentifiers.id_ecPublicKey.equals(alg.getAlgorithm())) {
            throw new IdentityException(Reason.UNSUPPORTED, file + ": key algorithm " + alg.getAlgorithm() + " is not EC");
        }
        ASN1Encodable curve = alg.getParameters();
        if (curve instanceof ASN1ObjectIdentifier && !SECObjectIdentifiers.secp256r1.equals(curve)) {
            throw new IdentityException(Reason.UNSUPPORTED, file + ": curve " + curve + " is not P-256");
        }
    }

    /**
     * Compares the domain parameters themselves: explicit encodings carry no curve name, and other
     * 256-bit curves exist. {@code ECDomainParameters.equals} leaves out the cofactor, so it is
     * checked here: it must be 1. An explicit encoding may omit it (it is optional in X9.62); with
     * P-256's own curve, generator and order that can only mean 1.
     */
    private static void requireP256(ECKeyParameters key, String file) throws IdentityException {
        ECDomainParameters p = key.getParameters();
        if (!P256.equals(p)) {
            throw new IdentityException(Reason.UNSUPPORTED, file + ": the key is not on the P-256 curve");
        }
        if (p.getH() != null && !BigInteger.ONE.equals(p.getH())) {
            throw new IdentityException(Reason.UNSUPPORTED, file + ": P-256 parameters with cofactor " + p.getH() + " instead of 1");
        }
    }

    private static byte[] read(File f) throws IdentityException {
        byte[] buf = new byte[MAX_FILE_LENGTH + 1];
        int n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
            if (n == 0) throw new IdentityException(Reason.LENGTH, f.getName() + " is empty");
            if (n > MAX_FILE_LENGTH) throw new IdentityException(Reason.LENGTH, f.getName() + " is larger than " + MAX_FILE_LENGTH + " bytes");
            return Arrays.copyOf(buf, n);
        } catch (IOException e) {
            throw new IdentityException(Reason.UNREADABLE, f.getName() + " cannot be read (" + e.getMessage() + ")", e);
        } finally {
            Arrays.fill(buf, (byte) 0);
        }
    }

    public enum Reason { UNREADABLE, LENGTH, MALFORMED, UNSUPPORTED, MISMATCHED }

    /** Why an identity was not loaded. Never carries key material. */
    public static final class IdentityException extends GeneralSecurityException {
        public final Reason reason;

        IdentityException(Reason reason, String message) {
            this(reason, message, null);
        }

        IdentityException(Reason reason, String message, Throwable cause) {
            super(reason + ": " + message, cause);
            this.reason = reason;
        }
    }
}
