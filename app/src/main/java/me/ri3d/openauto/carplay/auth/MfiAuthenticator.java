package me.ri3d.openauto.carplay.auth;

import java.security.GeneralSecurityException;

/**
 * What a CarPlay session needs from an accessory identity, wherever that identity lives: the
 * certificate to send and a signature over the phone's challenge. Implementations must be safe to
 * share between threads.
 */
public interface MfiAuthenticator {
    /** Authentication protocol major version; 3 is ECDSA over P-256. */
    int protocolMajor();

    /**
     * A fresh copy of the certificate container exactly as loaded.
     *
     * @throws IllegalArgumentException if the certificate is longer than {@code maximumOutputLength}
     */
    byte[] readCertificate(int maximumOutputLength);

    /**
     * Signs a 32-byte challenge digest as given (it is not hashed again).
     *
     * @return raw {@code r || s}, 64 bytes, unsigned big-endian
     * @throws IllegalArgumentException if {@code digest} is not exactly 32 bytes
     */
    byte[] signDigest(byte[] digest) throws GeneralSecurityException;
}
