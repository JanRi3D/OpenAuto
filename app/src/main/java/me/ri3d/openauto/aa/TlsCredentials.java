package me.ri3d.openauto.aa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The head unit's TLS client certificate and private key as DER bytes, loaded from PEM. The pair
 * shipped in assets/tls/ is the one every open-source receiver uses (see docs/DESIGN.md §1 for
 * provenance); replace the two files to use different credentials. Kept as raw DER on purpose:
 * Android 4.1's X.509 parser rejects this certificate's BER-style UTCTime, BouncyCastle does not.
 */
public final class TlsCredentials {
    public final byte[] certificateDer;
    public final byte[] privateKeyPkcs8;

    public TlsCredentials(byte[] certificateDer, byte[] privateKeyPkcs8) {
        this.certificateDer = certificateDer;
        this.privateKeyPkcs8 = privateKeyPkcs8;
    }

    /** {@code keyPem} must be PKCS#8 ("BEGIN PRIVATE KEY"); the asset is converted with openssl pkcs8. */
    public static TlsCredentials load(InputStream certPem, InputStream keyPem) throws IOException {
        return new TlsCredentials(pemBody(readAll(certPem), "CERTIFICATE"), pemBody(readAll(keyPem), "PRIVATE KEY"));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** Extracts and base64-decodes the body between the BEGIN/END lines of the given PEM type. */
    public static byte[] pemBody(byte[] pem, String type) throws IOException {
        String s = new String(pem, "US-ASCII");
        String begin = "-----BEGIN " + type + "-----", end = "-----END " + type + "-----";
        int a = s.indexOf(begin), z = s.indexOf(end);
        if (a < 0 || z < 0) throw new IOException("PEM block '" + type + "' not found");
        return base64(s.substring(a + begin.length(), z));
    }

    /** RFC 4648 base64 decoder, whitespace-tolerant. java.util.Base64 needs API 26; android.util.Base64 is not on the JVM. */
    static byte[] base64(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
        int acc = 0, bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            else throw new IOException("bad base64 character: " + c);
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
