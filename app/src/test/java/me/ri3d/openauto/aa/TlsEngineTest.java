package me.ri3d.openauto.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** Real TLS 1.2 handshake with client certificate between the BouncyCastle engine and a JSSE server, records carried by hand. */
public class TlsEngineTest {

    @Test
    public void handshakeAndRecordsRoundTrip() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        LoopbackTlsServer phone = new LoopbackTlsServer();
        TlsEngine engine = new TlsEngine(creds);
        try {
            phone.startHandshake();
            byte[] clientFlight = engine.beginHandshake();
            assertTrue("ClientHello expected", clientFlight.length > 0);
            assertEquals(0x16, clientFlight[0] & 0xFF); // handshake record
            int rounds = 0;
            while (!engine.isHandshakeDone()) {
                byte[] serverFlight = phone.exchange(clientFlight);
                clientFlight = engine.continueHandshake(serverFlight, 0, serverFlight.length);
                assertTrue("handshake did not converge", ++rounds < 6);
            }
            if (clientFlight.length > 0) phone.exchange(clientFlight);
            assertTrue(String.valueOf(phone.hsError), phone.hsError == null);
            assertTrue(engine.cipherSuite(), engine.cipherSuite().startsWith("TLSv1.2"));
            assertTrue("server must have seen our certificate", phone.ssl.getSession().getPeerCertificates().length == 1);

            // head unit -> phone: encrypt() yields exactly the records the server can decrypt
            byte[] plain = new byte[3000];
            new Random(3).nextBytes(plain);
            byte[] rec = new byte[70000];
            int n = engine.encrypt(plain, 0, plain.length, rec, 10);
            assertEquals(0x17, rec[10] & 0xFF);
            assertArrayEquals(plain, phone.decrypt(java.util.Arrays.copyOfRange(rec, 10, 10 + n)));

            // phone -> head unit: a 16 KiB chunk and small ones, each decrypted per frame
            for (int size : new int[]{16384, 1, 777}) {
                byte[] msg = new byte[size];
                new Random(size).nextBytes(msg);
                byte[] records = phone.encrypt(msg);
                byte[] out = new byte[20000];
                int m = engine.decrypt(records, 0, records.length, out, 0);
                assertArrayEquals("size " + size, msg, java.util.Arrays.copyOf(out, m));
            }
        } finally {
            engine.close();
            phone.close();
        }
    }

    @Test
    public void loadsShippedCredentials() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        org.bouncycastle.asn1.x509.Certificate c = org.bouncycastle.asn1.x509.Certificate.getInstance(creds.certificateDer);
        assertTrue(c.getIssuer().toString(), c.getIssuer().toString().contains("Google Automotive Link"));
        assertEquals(1217, creds.privateKeyPkcs8.length);
    }
}
