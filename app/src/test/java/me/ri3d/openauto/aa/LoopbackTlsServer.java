package me.ri3d.openauto.aa;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Test double for the phone's TLS end: a JSSE server-mode SSLSocket over a loopback pair, with raw
 * record access on the other end. A different TLS implementation than the engine under test
 * (JSSE vs BouncyCastle), so the two cross-check each other. Uses its own self-signed certificate
 * from src/test/resources and requires the head unit's client certificate.
 */
final class LoopbackTlsServer {
    final SSLSocket ssl;
    final Socket raw;
    final InputStream rawIn, sslIn;
    final OutputStream rawOut, sslOut;
    private Thread hs;
    volatile Exception hsError;
    volatile boolean hsDone;

    /** The head unit's credentials (client side) from the app assets. */
    static TlsCredentials testCredentials() throws Exception {
        try (InputStream c = new FileInputStream("src/main/assets/tls/headunit-cert.pem");
             InputStream k = new FileInputStream("src/main/assets/tls/headunit-key.pk8.pem")) {
            return TlsCredentials.load(c, k);
        }
    }

    LoopbackTlsServer() throws Exception {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket a = new Socket();
        a.connect(new InetSocketAddress("127.0.0.1", ss.getLocalPort()), 2000);
        Socket b = ss.accept();
        ss.close();
        a.setTcpNoDelay(true);
        b.setTcpNoDelay(true);

        byte[] certDer = TlsCredentials.pemBody(Files.readAllBytes(Paths.get("src/test/resources/fakephone-cert.pem")), "CERTIFICATE");
        byte[] keyDer = TlsCredentials.pemBody(Files.readAllBytes(Paths.get("src/test/resources/fakephone-key.pk8.pem")), "PRIVATE KEY");
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certDer));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("srv", key, new char[0], new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String t) { }
            public void checkServerTrusted(X509Certificate[] c, String t) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        ssl = (SSLSocket) ctx.getSocketFactory().createSocket(a, "127.0.0.1", a.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.setNeedClientAuth(true);
        ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
        raw = b;
        rawIn = b.getInputStream();
        rawOut = b.getOutputStream();
        sslIn = ssl.getInputStream();
        sslOut = ssl.getOutputStream();
    }

    void startHandshake() {
        hs = new Thread(() -> {
            try {
                ssl.startHandshake();
                hsDone = true;
            } catch (Exception e) {
                hsError = e;
            }
        });
        hs.start();
    }

    /** Feeds a client flight and returns the server's reply flight (whatever appears within a quiet period). */
    byte[] exchange(byte[] clientFlight) throws Exception {
        rawOut.write(clientFlight);
        rawOut.flush();
        return readFlight();
    }

    byte[] readFlight() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long quietSince = -1;
        long deadline = System.currentTimeMillis() + 5000;
        byte[] buf = new byte[65536];
        while (System.currentTimeMillis() < deadline) {
            if (hsError != null) throw hsError;
            int avail = rawIn.available();
            if (avail > 0) {
                int n = rawIn.read(buf, 0, Math.min(avail, buf.length));
                out.write(buf, 0, n);
                quietSince = -1;
                continue;
            }
            if (out.size() > 0) {
                if (quietSince < 0) quietSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - quietSince > 60) break;
            } else if (hsDone) {
                break;
            }
            Thread.sleep(2);
        }
        return out.toByteArray();
    }

    /** Encrypts plaintext into TLS record bytes (phone -> head unit payload). */
    byte[] encrypt(byte[] plain) throws Exception {
        sslOut.write(plain);
        sslOut.flush();
        return readRecords();
    }

    private byte[] readRecords() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] h = new byte[5];
        do {
            readFully(h, 0, 5);
            int len = ((h[3] & 0xFF) << 8) | (h[4] & 0xFF);
            byte[] body = new byte[len];
            readFully(body, 0, len);
            out.write(h);
            out.write(body);
        } while (rawIn.available() > 0);
        return out.toByteArray();
    }

    private void readFully(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = rawIn.read(b, off, len);
            if (n < 0) throw new IOException("closed");
            off += n;
            len -= n;
        }
    }

    /** Decrypts TLS record bytes (head unit -> phone payload). */
    byte[] decrypt(byte[] records) throws Exception {
        rawOut.write(records);
        rawOut.flush();
        byte[] buf = new byte[65536];
        int n = sslIn.read(buf);
        if (n < 0) throw new IOException("TLS closed");
        return java.util.Arrays.copyOf(buf, n);
    }

    void close() {
        try { ssl.close(); } catch (IOException ignored) { }
        try { raw.close(); } catch (IOException ignored) { }
    }
}
