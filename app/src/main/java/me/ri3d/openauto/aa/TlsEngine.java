package me.ri3d.openauto.aa;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Vector;

/**
 * TLS 1.2 client whose records travel inside protocol frames (protocol-reference §1.5, §9), built on
 * BouncyCastle's non-blocking {@link TlsClientProtocol}: handshake flights and application records go
 * in and out as byte arrays, so one frame payload maps to one plaintext chunk deterministically.
 *
 * Why not the platform stack: Android 4.1's SSLEngine is TLS 1.0 only, and its SSLSocket parses the
 * peer certificate with a strict DER parser inside a native callback, which rejects the BER-style
 * dates in the head-unit certificate and crashed the handshake in testing. BouncyCastle is pure Java
 * and behaves identically on API 16 and on the JVM, where the tests run. It is slow under Dalvik,
 * so old devices use {@link NativeTls} when their system library passes its self-test.
 *
 * The head unit does not verify the phone (as every open implementation does); the phone verifies us.
 */
public final class TlsEngine implements Tls {
    private final BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
    private final TlsClientProtocol protocol = new TlsClientProtocol();
    private final Object lock = new Object();
    private volatile boolean handshakeDone;
    private volatile int cipherSuite;
    private volatile String error;

    public TlsEngine(final me.ri3d.openauto.aa.TlsCredentials creds) throws IOException {
        final Certificate certificate;
        final AsymmetricKeyParameter privateKey;
        try {
            TlsCertificate tc = crypto.createCertificate(creds.certificateDer);
            certificate = new Certificate(new TlsCertificate[]{tc});
            privateKey = PrivateKeyFactory.createKey(PrivateKeyInfo.getInstance(creds.privateKeyPkcs8));
        } catch (RuntimeException e) {
            throw new IOException("head unit credentials unusable: " + e, e);
        }
        DefaultTlsClient client = new DefaultTlsClient(crypto) {
            @Override
            protected ProtocolVersion[] getSupportedVersions() {
                return ProtocolVersion.TLSv12.only();
            }

            @Override
            public TlsAuthentication getAuthentication() {
                return new TlsAuthentication() {
                    @Override
                    public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                        // Not verified: there is no public CA for the phone side (same as aasdk/headunit).
                    }

                    @Override
                    public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) {
                        SignatureAndHashAlgorithm alg = chooseRsa(certificateRequest.getSupportedSignatureAlgorithms());
                        return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto, privateKey, certificate, alg);
                    }
                };
            }

            @Override
            public void notifySelectedCipherSuite(int selectedCipherSuite) {
                super.notifySelectedCipherSuite(selectedCipherSuite);
                cipherSuite = selectedCipherSuite;
            }

            @Override
            public void notifyHandshakeComplete() throws IOException {
                super.notifyHandshakeComplete();
                handshakeDone = true;
            }

            @Override
            public void notifyAlertReceived(short alertLevel, short alertDescription) {
                error = "TLS alert from phone: level " + alertLevel + " description " + alertDescription;
            }
        };
        protocol.connect(client);
    }

    /** Prefers RSA + SHA-256 (what every TLS 1.2 server offers); falls back to any RSA algorithm offered. */
    @SuppressWarnings("rawtypes")
    static SignatureAndHashAlgorithm chooseRsa(Vector supported) {
        if (supported == null) return new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa);
        SignatureAndHashAlgorithm anyRsa = null;
        for (Object o : supported) {
            SignatureAndHashAlgorithm a = (SignatureAndHashAlgorithm) o;
            if (a.getSignature() != SignatureAlgorithm.rsa) continue;
            if (a.getHash() == HashAlgorithm.sha256) return a;
            if (anyRsa == null) anyRsa = a;
        }
        return anyRsa != null ? anyRsa : new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa);
    }

    // ---- handshake -----------------------------------------------------------------------------

    @Override
    public byte[] beginHandshake() throws IOException {
        synchronized (lock) {
            return drainOutput();
        }
    }

    @Override
    public byte[] continueHandshake(byte[] in, int off, int len) throws IOException {
        synchronized (lock) {
            protocol.offerInput(in, off, len);
            byte[] out = drainOutput();
            if (error != null && !handshakeDone) throw new IOException(error);
            return out;
        }
    }

    @Override
    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    private byte[] drainOutput() throws IOException {
        int n = protocol.getAvailableOutputBytes();
        byte[] out = new byte[n];
        if (n > 0) protocol.readOutput(out, 0, n);
        return out;
    }

    // ---- data path -----------------------------------------------------------------------------

    @Override
    public int encrypt(byte[] in, int inOff, int len, byte[] out, int outOff) throws IOException {
        synchronized (lock) {
            protocol.writeApplicationData(in, inOff, len);
            int n = protocol.getAvailableOutputBytes();
            if (outOff + n > out.length) throw new IOException("ciphertext does not fit frame buffer");
            protocol.readOutput(out, outOff, n);
            return n;
        }
    }

    @Override
    public int decrypt(byte[] in, int inOff, int inLen, byte[] out, int outOff) throws IOException {
        synchronized (lock) {
            protocol.offerInput(in, inOff, inLen);
            int n = protocol.getAvailableInputBytes();
            if (outOff + n > out.length) throw new IOException("plaintext does not fit message buffer");
            if (n > 0) protocol.readInput(out, outOff, n);
            if (protocol.isClosed()) throw new IOException(error != null ? error : "TLS connection closed by the phone");
            return n;
        }
    }

    @Override
    public String cipherSuite() {
        return "TLSv1.2 cipher 0x" + Integer.toHexString(cipherSuite) + " (Java engine)";
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                protocol.close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }
}
