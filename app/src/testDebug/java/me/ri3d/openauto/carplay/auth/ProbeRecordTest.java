package me.ri3d.openauto.carplay.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CancellationException;

/**
 * The probe's saved run record: every way a record can be unusable is reported as such, so the
 * probe skips the restart check and regenerates; a usable record with different values is a failed
 * check; anything else still propagates. Synthetic identities only.
 */
public class ProbeRecordTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private final SecureRandom random = new SecureRandom();

    // ---- unusable records (each found or suggested by review) ----

    @Test
    public void emptyRecordIsUnusable() throws Exception {
        assertUnusable("empty", new byte[0], "no pid");
    }

    @Test
    public void malformedUnicodeEscapeIsUnusable() throws Exception {
        assertUnusable("bad escape", ("pid=2468\nbroken=" + "\\" + "u12G4\n").getBytes(StandardCharsets.ISO_8859_1), "not a properties file");
    }

    @Test
    public void oneByteDigestIsUnusable() throws Exception {
        assertUnusable("digest=00", edited(valid(), "digest", "00"), "digest has 2 hex digits, expected 64");
    }

    @Test
    public void digestWithAnExtraHexDigitIsUnusable() throws Exception {
        Properties p = load(valid());
        assertUnusable("one digit too many", edited(valid(), "digest", p.getProperty("digest") + "F"), "digest has 65 hex digits");
    }

    @Test
    public void everyFieldIsRequiredAndStrict() throws Exception {
        byte[] good = valid();
        for (String key : new String[]{"pid", "digest", "der.certificate", "der.signature", "pkcs7.certificate", "pkcs7.signature"}) {
            Properties p = load(good);
            p.remove(key);
            assertUnusable("without " + key, store(p), "no " + key);
        }
        for (String pid : new String[]{"", "abc", "-5", "0", "1234567890", " 12", "1e3"}) {
            assertUnusable("pid " + pid, edited(good, "pid", pid), "process ID");
        }
        String digest = load(good).getProperty("digest");
        assertUnusable("a non-hex letter", edited(good, "digest", "G" + digest.substring(1)), "not a hex digit");
        assertUnusable("an Arabic-Indic digit", edited(good, "digest", "٣" + digest.substring(1)), "not a hex digit");
        assertUnusable("certificate hash one byte short", edited(good, "der.certificate", load(good).getProperty("der.certificate").substring(2)),
                "der.certificate has 62 hex digits, expected 64");
        assertUnusable("signature one byte short", edited(good, "pkcs7.signature", load(good).getProperty("pkcs7.signature").substring(2)),
                "pkcs7.signature has 126 hex digits, expected 128");
        assertUnusable("signature one byte long", edited(good, "pkcs7.signature", load(good).getProperty("pkcs7.signature") + "00"),
                "pkcs7.signature has 130 hex digits, expected 128");
    }

    // ---- usable records ----

    @Test
    public void validRecordReloadsAndAllChecksPass() throws Exception {
        File root = fixtures();
        SyntheticMfiProbe.Record written = recordFor(root);
        File file = new File(root, "record.properties");
        written.write(file);
        assertFalse("the temporary file is gone after the rename", new File(root, "record.properties.tmp").exists());
        SyntheticMfiProbe.Record read = SyntheticMfiProbe.Record.read(file);
        assertEquals(written.pid, read.pid);
        assertArrayEquals(written.digest, read.digest);
        List<String> results = verify(read, root);
        assertEquals(4, results.size());
        for (String r : results) assertTrue(r, r.startsWith("PASS"));
        // lower-case hex is as valid as the upper case the probe writes
        Properties p = load(Files.readAllBytes(file.toPath()));
        p.setProperty("digest", p.getProperty("digest").toLowerCase());
        Files.write(file.toPath(), store(p));
        assertArrayEquals(written.digest, SyntheticMfiProbe.Record.read(file).digest);
    }

    /** A well-formed record whose signature is not what the identity on disk produces: a real failure, reported as one. */
    @Test
    public void genuineMismatchIsAFailedCheckNotASkip() throws Exception {
        File root = fixtures();
        SyntheticMfiProbe.Record rec = recordFor(root);
        byte[] other = rec.signatures.get("pkcs7").clone();
        other[10] ^= 1;
        rec.signatures.put("pkcs7", other);
        File file = new File(root, "record.properties");
        rec.write(file);
        List<String> results = verify(SyntheticMfiProbe.Record.read(file), root);
        assertEquals(4, results.size());
        assertEquals("FAIL pkcs7: same deterministic signature as the earlier run", results.get(3));
        assertTrue(results.get(0).startsWith("PASS"));
    }

    @Test
    public void otherFailuresPropagate() throws Exception {
        File root = fixtures();
        SyntheticMfiProbe.Record rec = recordFor(root);
        File der = new File(root, "der");
        for (File f : der.listFiles()) assertTrue(f.delete());
        try {
            rec.verify(root, (ok, what) -> { });
            fail("a missing fixture was not reported");
        } catch (LocalMfiAuthenticator.IdentityException expected) {
            assertEquals(LocalMfiAuthenticator.Reason.UNREADABLE, expected.reason);
        }
        File good = fixtures();
        try {
            recordFor(good).verify(good, (ok, what) -> {
                throw new CancellationException("probe cancelled");
            });
            fail("cancellation was swallowed");
        } catch (CancellationException expected) {
            // the serialized worker relies on this reaching it
        }
    }

    // ---- helpers ----

    private void assertUnusable(String what, byte[] content, String reason) throws IOException {
        File file = tmp.newFile();
        Files.write(file.toPath(), content);
        try {
            SyntheticMfiProbe.Record.read(file);
            fail(what + ": accepted");
        } catch (SyntheticMfiProbe.Record.Unusable e) {
            assertTrue(what + ": " + e.getMessage(), e.getMessage().contains(reason));
        }
    }

    /** A record that reads cleanly, written by the probe's own writer. */
    private byte[] valid() throws Exception {
        File root = fixtures();
        File file = new File(root, "record.properties");
        recordFor(root).write(file);
        return Files.readAllBytes(file.toPath());
    }

    /** Two identity directories, der and pkcs7, like a probe run leaves them. */
    private File fixtures() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair kp = g.generateKeyPair();
        byte[] cert = LocalMfiAuthenticatorTest.certificate(kp.getPublic().getEncoded());
        File root = tmp.newFolder();
        write(new File(root, "der"), kp.getPrivate().getEncoded(), cert);
        write(new File(root, "pkcs7"), kp.getPrivate().getEncoded(), LocalMfiAuthenticatorTest.pkcs7(cert));
        return root;
    }

    /** What a run records for these fixtures: certificate hashes and the deterministic signature over a random digest. */
    private SyntheticMfiProbe.Record recordFor(File root) throws Exception {
        byte[] digest = new byte[32];
        random.nextBytes(digest);
        SyntheticMfiProbe.Record rec = new SyntheticMfiProbe.Record(4321, digest);
        for (String f : SyntheticMfiProbe.Record.FORMATS) {
            LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(new File(root, f));
            rec.certificateHashes.put(f, MessageDigest.getInstance("SHA-256").digest(auth.readCertificate(LocalMfiAuthenticator.MAX_FILE_LENGTH)));
            rec.signatures.put(f, auth.signDigest(digest));
        }
        return rec;
    }

    private static List<String> verify(SyntheticMfiProbe.Record rec, File root) throws Exception {
        List<String> results = new ArrayList<>();
        rec.verify(root, (ok, what) -> results.add((ok ? "PASS " : "FAIL ") + what));
        return results;
    }

    private static void write(File dir, byte[] key, byte[] certificate) throws IOException {
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, LocalMfiAuthenticator.KEY_FILE).toPath(), key);
        Files.write(new File(dir, LocalMfiAuthenticator.CERTIFICATE_FILE).toPath(), certificate);
    }

    private static Properties load(byte[] content) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new ByteArrayInputStream(content)) {
            p.load(in);
        }
        return p;
    }

    private static byte[] edited(byte[] content, String key, String value) throws IOException {
        Properties p = load(content);
        p.setProperty(key, value);
        return store(p);
    }

    private static byte[] store(Properties p) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream o = out) {
            p.store(o, "edited for a test");
        }
        return out.toByteArray();
    }
}
