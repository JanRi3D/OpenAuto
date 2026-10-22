package me.ri3d.openauto.carplay.auth;

import org.bouncycastle.crypto.signers.PlainDSAEncoding;
import org.bouncycastle.crypto.signers.StandardDSAEncoding;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SignatureException;
import java.util.Arrays;

/**
 * P-256 ECDSA signatures in the two encodings that meet here: raw {@code r || s} (32 bytes each,
 * unsigned big-endian, left-padded with zeros) as sent to the phone, and the DER
 * {@code SEQUENCE { INTEGER r, INTEGER s }} that JCA and most other libraries use.
 */
public final class RawEcdsaSignature {
    public static final int LENGTH = 64;
    /** SEQUENCE header plus two INTEGERs of at most 33 content bytes (a sign-padding zero and 32 value bytes). */
    static final int MAX_DER_LENGTH = 2 + 2 * (2 + 33);
    private static final BigInteger N = LocalMfiAuthenticator.P256.getN();

    private RawEcdsaSignature() {}

    /** Accepts canonical DER only: no extra or missing sign padding, minimal lengths, nothing after the sequence. */
    public static byte[] fromDer(byte[] der) throws SignatureException {
        if (der == null || der.length > MAX_DER_LENGTH) {
            throw new SignatureException("DER signature missing or longer than " + MAX_DER_LENGTH + " bytes");
        }
        BigInteger[] rs;
        try {
            rs = StandardDSAEncoding.INSTANCE.decode(N, der); // re-encodes and compares, so only canonical DER passes
        } catch (IOException | RuntimeException e) {
            throw new SignatureException("malformed DER signature", e);
        }
        return toRaw(rs[0], rs[1]);
    }

    public static byte[] toDer(byte[] raw) throws SignatureException {
        if (raw == null || raw.length != LENGTH) {
            throw new SignatureException("raw signature must be " + LENGTH + " bytes");
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, LENGTH / 2));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, LENGTH / 2, LENGTH));
        checkRange(r, s);
        try {
            return StandardDSAEncoding.INSTANCE.encode(N, r, s);
        } catch (IOException e) {
            throw new SignatureException(e);
        }
    }

    static byte[] toRaw(BigInteger r, BigInteger s) throws SignatureException {
        checkRange(r, s);
        return PlainDSAEncoding.INSTANCE.encode(N, r, s);
    }

    private static void checkRange(BigInteger r, BigInteger s) throws SignatureException {
        if (r.signum() <= 0 || s.signum() <= 0 || r.compareTo(N) >= 0 || s.compareTo(N) >= 0) {
            throw new SignatureException("r and s must lie in [1, n - 1]");
        }
    }
}
