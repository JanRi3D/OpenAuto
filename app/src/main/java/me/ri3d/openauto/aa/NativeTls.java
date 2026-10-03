package me.ri3d.openauto.aa;

import java.io.IOException;
import java.util.Arrays;

/**
 * TLS through the device's own OpenSSL, reached by the JNI shim in src/main/cpp/systls.c.
 *
 * Why: the pure-Java engine is too slow where this app matters most. On a real head unit
 * (Cortex-A7, Android 4.2, Dalvik) BouncyCastle encrypted about 0.25 MB/s; the reader thread used a
 * whole core and video arrived at 14 fps. Android 4.1 to 5.1 ship OpenSSL 1.0.1, which speaks
 * TLS 1.2 with AES-GCM in native code. Its Java wrappers cannot be used (see {@link TlsEngine}),
 * but the library itself can, and its certificate parser accepts the head-unit certificate.
 *
 * Nothing here is assumed to work: {@link #init} proves the library with a complete loopback
 * handshake and a record round trip before any session uses it, and every failure leaves the
 * BouncyCastle engine in charge.
 */
public final class NativeTls implements Tls {
    /** Newest Android whose system library is OpenSSL 1.0.1 (6.0 switched to BoringSSL, 7.0 closed it to apps). */
    public static final int MAX_SDK = 22;
    private static final int RECORD_ROOM = 16384 + 512; // one maximum TLS record plus header, nonce and tag

    private static volatile boolean usable;
    private static volatile String status = "not tried";

    private static native String nLibrary();
    private static native long nCreate(byte[] certDer, byte[] keyDer, boolean server);
    private static native void nFree(long handle);
    private static native String nError(long handle);
    private static native String nCipher(long handle);
    private static native int nHandshake(long handle, byte[] in, int inOff, int inLen, byte[] out, boolean[] done);
    private static native int nSeal(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff);
    private static native int nOpen(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff);

    /**
     * Loads the shim and runs the self-test once. Call from Android code only, off the UI thread
     * (two RSA operations). Returns whether sessions will use this engine.
     */
    public static synchronized boolean init(int sdkInt, TlsCredentials creds) {
        if (usable || !"not tried".equals(status)) return usable;
        if (sdkInt > MAX_SDK) {
            status = "not used on this Android version";
            return false;
        }
        try {
            // Makes the platform set OpenSSL's locking callbacks before another thread shares the library.
            javax.net.ssl.SSLContext.getInstance("TLS").init(null, null, null);
        } catch (Exception | LinkageError ignored) {
        }
        try {
            System.loadLibrary("systls");
            String library = nLibrary();
            selfTest(creds, 0);
            status = library;
            usable = true;
        } catch (IOException | LinkageError | RuntimeException e) {
            status = "unusable: " + e.getMessage();
        }
        return usable;
    }

    public static boolean usable() {
        return usable;
    }

    /** The system library's version string, or why it is not used. */
    public static String status() {
        return status;
    }

    /** Takes the engine out of service for this process, e.g. after a failed handshake with a phone. */
    public static void disable(String why) {
        usable = false;
        status = "switched off: " + why;
    }

    /** An engine for a new session, or null when the Java engine has to be used. */
    static Tls open(TlsCredentials creds) {
        if (!usable) return null;
        try {
            return new NativeTls(creds, false);
        } catch (IOException e) {
            disable(e.getMessage());
            return null;
        }
    }

    /**
     * Client and server engine against each other: handshake, then 8 KiB records one way for about
     * {@code millis} ms (a single record when 0). Returns the throughput in MB/s.
     */
    public static double selfTest(TlsCredentials creds, int millis) throws IOException {
        NativeTls client = new NativeTls(creds, false), server = new NativeTls(creds, true);
        try {
            byte[] flight = client.beginHandshake();
            for (int round = 0; round < 8 && !(client.isHandshakeDone() && server.isHandshakeDone()); round++) {
                flight = server.continueHandshake(flight, 0, flight.length);
                flight = client.continueHandshake(flight, 0, flight.length);
            }
            if (flight.length > 0) server.continueHandshake(flight, 0, flight.length);
            if (!client.isHandshakeDone() || !server.isHandshakeDone()) throw new IOException("self-test handshake did not complete");
            byte[] plain = new byte[8192], wire = new byte[RECORD_ROOM], back = new byte[8192];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 31);
            long bytes = 0, start = System.nanoTime(), end = start + millis * 1000000L;
            do {
                int n = client.encrypt(plain, 0, plain.length, wire, 0);
                int m = server.decrypt(wire, 0, n, back, 0);
                if (bytes == 0 && (m != plain.length || !Arrays.equals(plain, back))) throw new IOException("self-test record mismatch");
                bytes += m;
            } while (System.nanoTime() < end);
            return bytes / 1048576.0 / Math.max(1e-6, (System.nanoTime() - start) / 1e9);
        } finally {
            client.close();
            server.close();
        }
    }

    // ---- one engine ------------------------------------------------------------------------------

    private final Object lock = new Object();
    private final byte[] flight = new byte[RECORD_ROOM];
    private final boolean[] done = new boolean[1];
    private long handle;
    private volatile boolean handshakeDone;
    private String cipher = "";

    private NativeTls(TlsCredentials creds, boolean server) throws IOException {
        handle = nCreate(creds.certificateDer, creds.privateKeyPkcs8, server);
        if (handle == 0) throw new IOException("system OpenSSL rejected the head unit credentials or cipher list");
    }

    private long handle() throws IOException {
        if (handle == 0) throw new IOException("TLS engine closed");
        return handle;
    }

    private IOException error() {
        return new IOException(nError(handle));
    }

    @Override
    public byte[] beginHandshake() throws IOException {
        return continueHandshake(flight, 0, 0);
    }

    @Override
    public byte[] continueHandshake(byte[] in, int off, int len) throws IOException {
        synchronized (lock) {
            int n = nHandshake(handle(), in, off, len, flight, done);
            if (n < 0) throw error();
            if (done[0] && !handshakeDone) {
                cipher = nCipher(handle);
                handshakeDone = true;
            }
            return Arrays.copyOf(flight, n);
        }
    }

    @Override
    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    @Override
    public int encrypt(byte[] in, int inOff, int len, byte[] out, int outOff) throws IOException {
        synchronized (lock) {
            int n = nSeal(handle(), in, inOff, len, out, outOff);
            if (n < 0) throw error();
            return n;
        }
    }

    @Override
    public int decrypt(byte[] in, int inOff, int inLen, byte[] out, int outOff) throws IOException {
        synchronized (lock) {
            int n = nOpen(handle(), in, inOff, inLen, out, outOff);
            if (n == -2) throw new IOException("TLS connection closed by the phone");
            if (n < 0) throw error();
            return n;
        }
    }

    @Override
    public String cipherSuite() {
        return cipher + " (system OpenSSL)";
    }

    @Override
    public void close() {
        synchronized (lock) { // never frees under a call in progress
            if (handle != 0) nFree(handle);
            handle = 0;
        }
    }
}
