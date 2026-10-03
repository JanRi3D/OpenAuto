package me.ri3d.openauto.aa;

import java.io.Closeable;
import java.io.IOException;

/**
 * A TLS 1.2 client whose records travel inside protocol frames (protocol-reference §1.5, §9):
 * handshake flights and application records go in and out as byte arrays. Two implementations:
 * {@link NativeTls} (the device's own OpenSSL, fast on old devices) and {@link TlsEngine}
 * (BouncyCastle, pure Java, works everywhere including the JVM tests).
 */
public interface Tls extends FrameWriter.Encryptor, FrameAssembler.Decryptor, Closeable {
    /** The first client flight (ClientHello) to send as SSL_HANDSHAKE. */
    byte[] beginHandshake() throws IOException;

    /**
     * Feeds a server flight (payload of an incoming SSL_HANDSHAKE). Returns the next client flight,
     * or an empty array when the handshake completed and nothing more needs to be sent.
     */
    byte[] continueHandshake(byte[] in, int off, int len) throws IOException;

    boolean isHandshakeDone();

    /** Protocol version, cipher suite and which implementation, for the connection log. */
    String cipherSuite();

    @Override
    void close();
}
