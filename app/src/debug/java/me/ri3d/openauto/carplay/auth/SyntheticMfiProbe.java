package me.ri3d.openauto.carplay.auth;

import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECNamedDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.signers.StandardDSAEncoding;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.IdentityException;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator.Reason;
import me.ri3d.openauto.carplay.iap2.BluetoothProfile;
import me.ri3d.openauto.carplay.iap2.ControlSession;
import me.ri3d.openauto.carplay.iap2.CsmMessage;
import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;
import me.ri3d.openauto.carplay.iap2.Iap2Connection;
import me.ri3d.openauto.carplay.iap2.Iap2Link;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer;
import me.ri3d.openauto.diag.DeviceInfo;

/**
 * On-device check of {@link LocalMfiAuthenticator} with throwaway identities made here: P-256 keys,
 * certificates issued by a throwaway CA, written once as a DER certificate and once as a one-entry
 * PKCS#7 envelope, then loaded and used through the production loader and signer. Every result is
 * SYNTHETIC: it shows the code runs correctly on this runtime, not that a phone accepts any
 * identity. Reads and writes nothing outside {@link #DIR}; never shows or logs private keys.
 */
public final class SyntheticMfiProbe {
    /** The probe's own directory under getFilesDir(). "Delete fixtures" or uninstalling removes it. */
    static final String DIR = "carplay-auth-probe";
    /** The fixture a probe run leaves behind that other debug checks may load as their SYNTHETIC identity. */
    public static final String IDENTITY = "pkcs7";
    private static final String TAG = "MfiProbe";
    private static final String RECORD = "record.properties";
    private static final String[] FORMATS = Record.FORMATS;
    private static final int[] BAD_DIGEST_LENGTHS = {0, 20, 31, 33, 64};
    private static final int SIGNATURE_RUNS = 20;

    private final SecureRandom random = new SecureRandom(new UrandomSpi(), null) {};
    private final StringBuilder report = new StringBuilder();
    private final ProbeRunner.Cancel cancel;
    private int checks, failures;

    private SyntheticMfiProbe(ProbeRunner.Cancel cancel) {
        this.cancel = cancel;
    }

    /**
     * Runs every case and returns the report, which also goes to logcat (tag MfiProbe). Call it
     * only through {@link ProbeRunner}, which keeps it apart from every other use of the fixture
     * directory. Stops with a CancellationException at the next step once cancelled.
     */
    static String run(File filesDir, File apk, ProbeRunner.Cancel cancel) {
        SyntheticMfiProbe probe = new SyntheticMfiProbe(cancel);
        probe.runAll(new File(filesDir, DIR), apk);
        return probe.report.toString();
    }

    /** Where a probe run leaves the SYNTHETIC identity; it exists only after a run and until "Delete fixtures". */
    public static File identityDir(File filesDir) {
        return new File(new File(filesDir, DIR), IDENTITY);
    }

    /** Through {@link ProbeRunner} only, like {@link #run}. */
    static boolean deleteFixtures(File filesDir) {
        return delete(new File(filesDir, DIR));
    }

    private void runAll(File root, File apk) {
        long start = System.nanoTime();
        line("SYNTHETIC identities only: not Apple certification and not a CarPlay result.");
        line("Runtime  Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), ABI " + DeviceInfo.abis()
                + ", VM " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        line("Build    " + Build.FINGERPRINT);
        line("APK      SHA-256 " + apkSha256(apk));
        line("Backend  BouncyCastle lightweight ECDSASigner, RFC 6979 nonces (HMacDSAKCalculator, SHA-256), P-256");
        line("Process  pid " + Process.myPid() + ", fixtures in " + root);
        try {
            earlierRun(root);
            freshRun(root);
        } catch (CancellationException e) {
            throw e;
        } catch (Throwable e) { // a VerifyError or a missing class on an old runtime is a result too
            failures++;
            line("ERROR    " + e);
            Log.e(TAG, "probe aborted", e);
        }
        line("");
        line(String.format(Locale.US, "RESULT   %s: %d checks, %d failed, %.0f ms, SYNTHETIC",
                failures == 0 ? "PASS" : "FAIL", checks, failures, ms(start)));
    }

    /**
     * Checks the identities the previous run left on disk against its record; after "Restart
     * process" that run was another process. A record that cannot be used is reported and the
     * check skipped. A usable record whose values do not match is a failed check, and any other
     * failure (a fixture missing, a signer error) ends the run as an error.
     */
    private void earlierRun(File root) throws Exception {
        File file = new File(root, RECORD);
        if (!file.isFile()) {
            section("earlier run");
            line("none on disk; \"Restart process\" repeats this check in a new process after this run");
            return;
        }
        Record rec;
        try {
            rec = Record.read(file);
        } catch (Record.Unusable e) {
            section("earlier run");
            line("saved record unusable (" + e.getMessage() + "): restart check skipped, a fresh run follows");
            return;
        }
        section(rec.pid == Process.myPid() ? "reload in the same process (pid " + rec.pid + ")"
                : "restart: written by pid " + rec.pid + ", loaded by pid " + Process.myPid());
        rec.verify(root, this::check);
    }

    private void freshRun(File root) throws Exception {
        delete(root);
        section("fixtures");
        long t = System.nanoTime();
        AsymmetricCipherKeyPair ca = keyPair(), holder = keyPair();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) holder.getPublic();
        byte[] key = PrivateKeyInfoFactory.createPrivateKeyInfo(holder.getPrivate()).getEncoded(ASN1Encoding.DER);
        byte[] cert = certificate(pub, ca), p7b = pkcs7(cert);
        line("generated CA and accessory P-256 keys, certificate " + cert.length + " B, PKCS#7 envelope " + p7b.length + " B", t);

        byte[] digest = randomBytes(32);
        Record rec = new Record(Process.myPid(), digest);
        formatCase("der", "DER X.509 certificate", root, key, cert, pub, digest, rec);
        formatCase(IDENTITY, "PKCS#7 SignedData with one certificate", root, key, p7b, pub, digest, rec);
        iap2Session(new File(root, IDENTITY), pub);
        iap2Link(new File(root, IDENTITY), pub);
        knownAnswer(new File(root, "rfc6979"), ca);
        invalid(root, key, cert, p7b, ca);
        rec.write(new File(root, RECORD));
    }

    private void formatCase(String name, String title, File root, byte[] key, final byte[] container,
                            ECPublicKeyParameters pub, byte[] fixedDigest, Record rec) throws Exception {
        section(title + ", written as " + name + "/" + LocalMfiAuthenticator.CERTIFICATE_FILE);
        File dir = write(new File(root, name), key, container);
        long t = System.nanoTime();
        final LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(dir);
        check(auth.protocolMajor() == 3, "loaded, protocol major 3", t);
        byte[] a = auth.readCertificate(container.length), b = auth.readCertificate(container.length);
        check(Arrays.equals(a, container), "certificate bytes equal the file (" + container.length + " B)");
        a[0] ^= 1;
        check(a != b && Arrays.equals(b, container), "certificate copies are independent");
        check(rejects(() -> auth.readCertificate(container.length - 1)), "output limit below the certificate length rejected");

        byte[] digest = randomBytes(32);
        t = System.nanoTime();
        byte[] sig = auth.signDigest(digest);
        check(sig.length == RawEcdsaSignature.LENGTH && verify(pub, digest, sig), "64-byte signature verifies with the generated public key", t);
        byte[] altered = digest.clone();
        altered[0] ^= 1;
        check(!verify(pub, altered, sig), "the signature does not verify for an altered digest");
        for (final int len : BAD_DIGEST_LENGTHS) check(rejects(() -> auth.signDigest(new byte[len])), "digest of " + len + " bytes rejected");
        check(Arrays.equals(RawEcdsaSignature.fromDer(RawEcdsaSignature.toDer(sig)), sig), "raw -> DER -> raw round trip");

        byte[] fixedSig = auth.signDigest(fixedDigest);
        t = System.nanoTime();
        LocalMfiAuthenticator again = LocalMfiAuthenticator.load(dir);
        check(Arrays.equals(again.readCertificate(container.length), container) && Arrays.equals(again.signDigest(fixedDigest), fixedSig),
                "reload in this process: same certificate and signature", t);
        t = System.nanoTime();
        for (int i = 0; i < SIGNATURE_RUNS; i++) auth.signDigest(digest);
        line(String.format(Locale.US, "signing takes %.1f ms (mean of %d)", ms(t) / SIGNATURE_RUNS, SIGNATURE_RUNS));
        rec.certificateHashes.put(name, sha256(container));
        rec.signatures.put(name, fixedSig);
    }

    /** RFC 6979 A.2.5 (P-256, SHA-256, message "sample") through the production loader and signer. */
    private void knownAnswer(File dir, AsymmetricCipherKeyPair ca) throws Exception {
        section("RFC 6979 known answer (the RFC's published test key)");
        ECNamedDomainParameters p256 = ECNamedDomainParameters.lookup(SECObjectIdentifiers.secp256r1);
        BigInteger x = new BigInteger("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16);
        ECPublicKeyParameters pub = new ECPublicKeyParameters(p256.getG().multiply(x).normalize(), p256);
        check(hex(pub.getQ().getAffineXCoord().getEncoded()).equals("60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6"),
                "public key matches the RFC");
        write(dir, PrivateKeyInfoFactory.createPrivateKeyInfo(new ECPrivateKeyParameters(x, p256)).getEncoded(ASN1Encoding.DER), certificate(pub, ca));
        long t = System.nanoTime();
        byte[] sig = LocalMfiAuthenticator.load(dir).signDigest(sha256("sample".getBytes(Charset.forName("US-ASCII"))));
        check(hex(sig).equals("EFD48B2AACB6A8FD1140DD9CD45E81D69D2C877B56AAF991C34D0EA84EAF3716"
                + "F7CB1C942D657C41D436C7A1B6E29F65F3E900DBB9AFF4064DC4AB2F843ACDA8"), "signature equals the RFC's r || s", t);
    }

    /** The control session against a phone scripted here. Its acceptance is SIMULATED: a test result, never an iPhone's. */
    private void iap2Session(File identityDir, ECPublicKeyParameters pub) throws Exception {
        section("iAP2 control session, phone SIMULATED in this process (not an iPhone)");
        final List<CsmMessage> sent = new ArrayList<>();
        Iap2Link link = message -> sent.add(CsmMessage.decode(message, 0, message.length));
        long t = System.nanoTime();
        ControlSession s = new ControlSession(link, LocalMfiAuthenticator.load(identityDir), probeIdentification("SYNTHETIC probe"), 10_000, 0);
        byte[] challenge = randomBytes(32);
        byteByByte(s, new CsmMessage(ControlSession.START_IDENTIFICATION), new CsmMessage(ControlSession.IDENTIFICATION_ACCEPTED),
                new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE),
                new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, new Parameter(0, challenge)));
        boolean shape = sent.size() == 3 && sent.get(0).id == ControlSession.IDENTIFICATION_INFORMATION
                && sent.get(1).id == ControlSession.AUTHENTICATION_CERTIFICATE && sent.get(2).id == ControlSession.AUTHENTICATION_RESPONSE;
        check(shape, "identification, certificate and signature sent; phone messages fed byte by byte");
        check(shape && verify(pub, challenge, sent.get(2).single(0)), "the signature over the phone's challenge verifies");
        check(!s.isIdentifiedAndAuthenticated(), "not identified and authenticated on a sent signature alone");
        byteByByte(s, new CsmMessage(ControlSession.AUTHENTICATION_SUCCEEDED));
        check(s.isIdentifiedAndAuthenticated(), "identified and authenticated after the SIMULATED phone's verdicts (test result)", t);

        ControlSession refused = new ControlSession(link, LocalMfiAuthenticator.load(identityDir), probeIdentification("SYNTHETIC probe"), 10_000, 0);
        byteByByte(refused, new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE),
                new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, new Parameter(0, challenge)),
                new CsmMessage(ControlSession.AUTHENTICATION_FAILED));
        check(refused.failure() == ControlSession.Failure.AUTHENTICATION_FAILED && !refused.isIdentifiedAndAuthenticated(),
                "a SIMULATED AuthenticationFailed ends the session");
    }

    /**
     * The Bluetooth profile end to end, link layer included, against a phone link scripted here.
     * SIMULATED: a test result. Not iPhone acceptance, and CarPlay is not implemented.
     */
    private void iap2Link(File identityDir, ECPublicKeyParameters pub) throws Exception {
        section("iAP2 Bluetooth profile, link and control session, phone SIMULATED in this process (not an iPhone, no CarPlay)");
        LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(identityDir);
        long t = System.nanoTime();
        ProbePhone phone = connected(auth, "Probe \u00dcn\u00efcode N\u00e4me");
        Iap2Connection acc = phone.acc;
        Iap2LinkLayer.Parameters n = acc.link().negotiated();
        check(phone.detectFirst, "detection bytes first, the SYN only after their echo");
        check(acc.link().state() == Iap2LinkLayer.State.UP && n.maxPacketLength == 48 && acc.link().sendWindow() == 2
                && n.sessions.get(0).id == BluetoothProfile.CONTROL_SESSION_ID, "link synchronized on the phone's 48-byte packets, window 2, control session 10", t);

        phone.send(new CsmMessage(ControlSession.START_IDENTIFICATION), true);
        CsmMessage first = phone.lastMessage();
        List<Integer> ids = new ArrayList<>();
        for (Parameter p : first.parameters()) ids.add(p.id);
        check(ids.equals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 17)),
                "identification carries the profile's 13 parameters and nothing else (no CarPlay, External Accessory, HID or USB): " + ids);
        phone.send(new CsmMessage(ControlSession.IDENTIFICATION_REJECTED, new Parameter(0, new byte[0])), false);
        CsmMessage second = phone.lastMessage();
        check(second != first && second.id == ControlSession.IDENTIFICATION_INFORMATION
                        && Arrays.equals(second.parameters().get(0).value(), "OpenAuto\0".getBytes(Charset.forName("US-ASCII")))
                        && sameAfterFirst(first, second),
                "rejected Name corrected to the default, every other parameter unchanged");
        phone.send(new CsmMessage(ControlSession.IDENTIFICATION_ACCEPTED), false);
        int before = phone.dataPackets;
        phone.send(new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CERTIFICATE), false);
        byte[] container = phone.last(ControlSession.AUTHENTICATION_CERTIFICATE);
        check(container != null && Arrays.equals(container, auth.readCertificate(LocalMfiAuthenticator.MAX_FILE_LENGTH)),
                "certificate response arrived intact over " + (phone.dataPackets - before) + " packets of at most 48 bytes");
        byte[] challenge = randomBytes(32);
        phone.send(new CsmMessage(ControlSession.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE, new Parameter(0, challenge)), false);
        byte[] sig = phone.last(ControlSession.AUTHENTICATION_RESPONSE);
        check(sig != null && verify(pub, challenge, sig), "the signature over the phone's challenge verifies");
        check(!acc.isIdentifiedAndAuthenticated(), "not identified and authenticated on a sent signature alone");
        phone.send(new CsmMessage(ControlSession.AUTHENTICATION_SUCCEEDED), false);
        check(acc.isIdentifiedAndAuthenticated(), "identified and authenticated after the SIMULATED verdicts (test result; not iPhone acceptance, no CarPlay)", t);
        check(phone.accessoryWrapped && phone.seq < 253, "sequence numbers wrapped past 255 on both sides");
        check(phone.corruptSent > 0 && phone.duplicatesSent > 0, "a damaged copy and a repeat of phone packets were dropped on the way; " + acc.link().stats());
        phone.deliver(Iap2LinkLayer.packet(Iap2LinkLayer.RST, phone.seq, phone.ack, 0, new byte[0]), false);
        check(acc.link().state() == Iap2LinkLayer.State.DOWN && acc.session().failure() == ControlSession.Failure.DISCONNECTED && !acc.isIdentifiedAndAuthenticated(),
                "RST from the phone ends the link and the session");

        ProbePhone strict = connected(auth, "SYNTHETIC probe");
        strict.send(new CsmMessage(ControlSession.START_IDENTIFICATION), false);
        strict.send(new CsmMessage(ControlSession.IDENTIFICATION_REJECTED, new Parameter(17, new byte[0])), false);
        ControlSession failed = strict.acc.session();
        check(failed.failure() == ControlSession.Failure.IDENTIFICATION_REJECTED && failed.failureDetail().contains("BluetoothTransportComponent")
                        && strict.acc.link().downReason() == Iap2LinkLayer.Down.CLOSED,
                "a rejected mandatory field (BluetoothTransportComponent) ends the session and closes the link: " + failed.failureDetail());
    }

    /** A Bluetooth profile connection synchronized with a phone link scripted here: echo, then a SYN+ACK on the proposed session with 48-byte packets. */
    private static ProbePhone connected(LocalMfiAuthenticator auth, String name) throws Exception {
        List<byte[]> toPhone = new ArrayList<>();
        Iap2Connection acc = new Iap2Connection(toPhone::add, BluetoothProfile.linkProposal(), BluetoothProfile.DETECT, auth, probeIdentification(name), 10_000);
        acc.start(0, 254);
        byte[] detect = toPhone.remove(0);
        ProbePhone phone = new ProbePhone(acc, toPhone, 253);
        phone.detectFirst = Arrays.equals(detect, BluetoothProfile.DETECT) && toPhone.isEmpty();
        acc.receive(detect, 0, detect.length, 0); // the phone's echo
        byte[] syn = toPhone.remove(0);
        byte[] answer = new Iap2LinkLayer.Parameters(1, 2, 48, 1000, 20, 5, 1,
                new Iap2LinkLayer.SessionEntry(BluetoothProfile.CONTROL_SESSION_ID, 0, BluetoothProfile.CONTROL_SESSION_VERSION)).encode();
        phone.deliver(Iap2LinkLayer.packet(Iap2LinkLayer.SYN | Iap2LinkLayer.ACK, 253, syn[5] & 0xFF, 0, answer), false);
        return phone;
    }

    /** SYNTHETIC identity values and a made-up Bluetooth address. */
    private static BluetoothProfile.Identification probeIdentification(String name) {
        return new BluetoothProfile.Identification(name, "OA-PROBE", "OpenAuto", "SYNTHETIC-0001", "probe", "1",
                new byte[]{0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F});
    }

    private static boolean sameAfterFirst(CsmMessage a, CsmMessage b) {
        if (a.parameters().size() != b.parameters().size()) return false;
        for (int i = 1; i < a.parameters().size(); i++) {
            if (a.parameters().get(i).id != b.parameters().get(i).id || !Arrays.equals(a.parameters().get(i).value(), b.parameters().get(i).value())) {
                return false;
            }
        }
        return true;
    }

    /** The phone's link for {@link #iap2Link}: acknowledges every accessory packet at once and reads its control session bytes. */
    private static final class ProbePhone {
        final Iap2Connection acc;
        final List<byte[]> fromAccessory;
        final CsmMessage.Reader reader = new CsmMessage.Reader();
        final List<CsmMessage> received = new ArrayList<>();
        int seq, ack, dataPackets, corruptSent, duplicatesSent;
        boolean accessoryWrapped, detectFirst;

        ProbePhone(Iap2Connection acc, List<byte[]> fromAccessory, int initialSeq) {
            this.acc = acc;
            this.fromAccessory = fromAccessory;
            this.seq = initialSeq;
        }

        /** Splits the message into packets of 38 bytes of payload; every third packet goes first as a damaged copy, then twice. */
        void send(CsmMessage m, boolean bytewise) throws Exception {
            byte[] b = m.encode();
            for (int off = 0; off < b.length; off += 38) {
                seq = (seq + 1) & 0xFF;
                byte[] packet = Iap2LinkLayer.packet(Iap2LinkLayer.ACK, seq, ack, BluetoothProfile.CONTROL_SESSION_ID,
                        Arrays.copyOfRange(b, off, Math.min(b.length, off + 38)));
                if (seq % 3 == 0) {
                    byte[] damaged = packet.clone();
                    damaged[damaged.length - 1] ^= 1;
                    deliver(damaged, false);
                    corruptSent++;
                }
                deliver(packet, bytewise);
                if (seq % 3 == 0) {
                    deliver(packet, false);
                    duplicatesSent++;
                }
            }
        }

        void deliver(byte[] packet, boolean bytewise) throws Exception {
            if (bytewise) {
                for (int i = 0; i < packet.length; i++) acc.receive(packet, i, 1, 0);
            } else {
                acc.receive(packet, 0, packet.length, 0);
            }
            while (!fromAccessory.isEmpty()) {
                byte[] p = fromAccessory.remove(0);
                if (p.length <= Iap2LinkLayer.HEADER_LENGTH || (p[7] & 0xFF) != BluetoothProfile.CONTROL_SESSION_ID) continue;
                if ((p[5] & 0xFF) == 0 && ack == 255) accessoryWrapped = true;
                ack = p[5] & 0xFF;
                dataPackets++;
                received.addAll(reader.feed(p, Iap2LinkLayer.HEADER_LENGTH, p.length - Iap2LinkLayer.HEADER_LENGTH - 1));
                byte[] ackPacket = Iap2LinkLayer.packet(Iap2LinkLayer.ACK, seq, ack, 0, new byte[0]);
                acc.receive(ackPacket, 0, ackPacket.length, 0);
            }
        }

        CsmMessage lastMessage() {
            return received.isEmpty() ? null : received.get(received.size() - 1);
        }

        /** Parameter 0 of the last message the accessory sent, if it has this ID. */
        byte[] last(int id) throws Exception {
            CsmMessage m = lastMessage();
            return m != null && m.id == id ? m.single(0) : null;
        }
    }

    private static void byteByByte(ControlSession s, CsmMessage... messages) {
        for (CsmMessage m : messages) {
            byte[] b = m.encode();
            for (int i = 0; i < b.length; i++) s.receive(b, i, 1, 0);
        }
    }

    private void invalid(File root, byte[] key, byte[] cert, byte[] p7b, AsymmetricCipherKeyPair ca) throws Exception {
        section("invalid identities, each must be refused");
        byte[] caCert = certificate((ECPublicKeyParameters) ca.getPublic(), ca);
        refused(new File(root, "mismatched"), key, caCert, Reason.MISMATCHED, "key of another certificate");
        refused(new File(root, "two-certificates"), key, pkcs7(cert, caCert), Reason.UNSUPPORTED, "envelope with two certificates");
        refused(new File(root, "no-certificate"), key, pkcs7(), Reason.UNSUPPORTED, "envelope without a certificate");
        refused(new File(root, "truncated"), key, Arrays.copyOf(p7b, p7b.length - 1), Reason.MALFORMED, "truncated envelope");
        refused(new File(root, "oversized"), key, Arrays.copyOf(cert, LocalMfiAuthenticator.MAX_FILE_LENGTH + 1), Reason.LENGTH, "certificate file over 16 KiB");
    }

    private void refused(File dir, byte[] key, byte[] container, Reason expected, String what) throws IOException {
        write(dir, key, container);
        try {
            LocalMfiAuthenticator.load(dir);
            check(false, what + ": loaded although invalid");
        } catch (IdentityException e) {
            check(e.reason == expected, what + ": " + e.getMessage());
        }
    }

    // ---- synthetic material ----

    private AsymmetricCipherKeyPair keyPair() {
        ECKeyPairGenerator g = new ECKeyPairGenerator();
        g.init(new ECKeyGenerationParameters(ECNamedDomainParameters.lookup(SECObjectIdentifiers.secp256r1), random));
        return g.generateKeyPair();
    }

    /** An X.509 certificate for {@code subject} issued by {@code ca}: SHA-256 over the TBS bytes, then ECDSA. */
    private static byte[] certificate(ECPublicKeyParameters subject, AsymmetricCipherKeyPair ca) throws IOException {
        AlgorithmIdentifier alg = new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
        V3TBSCertificateGenerator gen = new V3TBSCertificateGenerator();
        gen.setSerialNumber(new ASN1Integer(System.nanoTime() & Long.MAX_VALUE));
        gen.setSignature(alg);
        gen.setIssuer(new X500Name("CN=SYNTHETIC probe CA"));
        gen.setSubject(new X500Name("CN=SYNTHETIC probe accessory"));
        gen.setStartDate(new Time(new Date(0)));
        gen.setEndDate(new Time(new Date(2524608000000L))); // 2050
        gen.setSubjectPublicKeyInfo(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(subject));
        TBSCertificate tbs = gen.generateTBSCertificate();
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, ca.getPrivate());
        BigInteger[] rs = signer.generateSignature(sha256(tbs.getEncoded(ASN1Encoding.DER)));
        byte[] sig = StandardDSAEncoding.INSTANCE.encode(LocalMfiAuthenticator.P256.getN(), rs[0], rs[1]);
        return new DERSequence(new ASN1Encodable[]{tbs, alg, new DERBitString(sig)}).getEncoded(ASN1Encoding.DER);
    }

    /** A certificates-only PKCS#7 SignedData envelope. */
    private static byte[] pkcs7(byte[]... certificates) throws IOException {
        ASN1EncodableVector v = new ASN1EncodableVector();
        for (byte[] c : certificates) v.add(ASN1Primitive.fromByteArray(c));
        SignedData sd = new SignedData(new ASN1Integer(1), new DERSet(), new ContentInfo(PKCSObjectIdentifiers.data, null), new DERSet(v), null, new DERSet());
        return new ContentInfo(PKCSObjectIdentifiers.signedData, sd).getEncoded(ASN1Encoding.DER);
    }

    private static boolean verify(ECPublicKeyParameters pub, byte[] digest, byte[] raw) {
        ECDSASigner v = new ECDSASigner();
        v.init(false, pub);
        return v.verifySignature(digest, new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)), new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)));
    }

    /**
     * /dev/urandom for this probe only. Android 4.1–4.3's default SecureRandom can be badly seeded,
     * and the usual fix replaces the process-wide provider, which would also reach the Android Auto TLS path.
     */
    private static final class UrandomSpi extends SecureRandomSpi {
        @Override
        protected void engineSetSeed(byte[] seed) {
            // the kernel pool needs nothing from here
        }

        @Override
        protected void engineNextBytes(byte[] bytes) {
            try (DataInputStream in = new DataInputStream(new FileInputStream("/dev/urandom"))) {
                in.readFully(bytes);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read /dev/urandom", e);
            }
        }

        @Override
        protected byte[] engineGenerateSeed(int numBytes) {
            byte[] b = new byte[numBytes];
            engineNextBytes(b);
            return b;
        }
    }

    // ---- report and file helpers ----

    private void section(String title) {
        line("");
        line("== " + title);
    }

    private void line(String text) {
        if (cancel.cancelled()) throw new CancellationException("probe cancelled");
        report.append(text).append('\n');
        Log.i(TAG, text);
    }

    private void line(String text, long start) {
        line(String.format(Locale.US, "%s (%.1f ms)", text, ms(start)));
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        line((ok ? "SYNTHETIC PASS  " : "SYNTHETIC FAIL  ") + what);
    }

    private void check(boolean ok, String what, long start) {
        check(ok, String.format(Locale.US, "%s (%.1f ms)", what, ms(start)));
    }

    private static boolean rejects(Callable<?> call) throws Exception {
        try {
            call.call();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static double ms(long start) {
        return (System.nanoTime() - start) / 1e6;
    }

    private static File write(File dir, byte[] key, byte[] certificate) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(new File(dir, LocalMfiAuthenticator.KEY_FILE))) {
            out.write(key);
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, LocalMfiAuthenticator.CERTIFICATE_FILE))) {
            out.write(certificate);
        }
        return dir;
    }

    private static boolean delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) delete(c);
        return f.delete() || !f.exists();
    }

    private static String apkSha256(File apk) {
        try (InputStream in = new FileInputStream(apk)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unavailable (" + e + ")";
        }
    }

    private static byte[] sha256(byte[] data) {
        SHA256Digest d = new SHA256Digest();
        byte[] out = new byte[d.getDigestSize()];
        d.update(data, 0, data.length);
        d.doFinal(out, 0);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }

    /**
     * The run record: one digest, and per format the SHA-256 of the certificate container and the
     * deterministic signature over that digest. Written at the end of a run, read by the next.
     * Reading it is the one place where a bad file is expected (power lost before the write reached
     * the disk, an older version, an edit by hand), so {@link #read} turns every way the file can be
     * unusable into {@link Unusable} and catches nothing else.
     */
    static final class Record {
        static final String[] FORMATS = {"der", "pkcs7"};
        static final int DIGEST_BYTES = 32, HASH_BYTES = 32, SIGNATURE_BYTES = RawEcdsaSignature.LENGTH;

        final int pid;
        final byte[] digest;
        final Map<String, byte[]> certificateHashes = new HashMap<>(), signatures = new HashMap<>();

        Record(int pid, byte[] digest) {
            this.pid = pid;
            this.digest = digest.clone();
        }

        /** Every field present, a positive process ID, and exact-length hexadecimal values. */
        static Record read(File file) throws Unusable {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                throw new Unusable("cannot be read: " + e.getMessage());
            } catch (IllegalArgumentException e) { // what Properties.load throws for a malformed Unicode escape
                throw new Unusable("not a properties file: " + e.getMessage());
            }
            Record r = new Record(processId(p), hex(p, "digest", DIGEST_BYTES));
            for (String f : FORMATS) {
                r.certificateHashes.put(f, hex(p, f + ".certificate", HASH_BYTES));
                r.signatures.put(f, hex(p, f + ".signature", SIGNATURE_BYTES));
            }
            return r;
        }

        /** Written whole or not at all: a temporary file, flushed to the disk, then renamed over the record. */
        void write(File file) throws IOException {
            Properties p = new Properties();
            p.setProperty("pid", String.valueOf(pid));
            p.setProperty("digest", SyntheticMfiProbe.hex(digest));
            for (String f : FORMATS) {
                p.setProperty(f + ".certificate", SyntheticMfiProbe.hex(certificateHashes.get(f)));
                p.setProperty(f + ".signature", SyntheticMfiProbe.hex(signatures.get(f)));
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                p.store(out, "SYNTHETIC probe run: certificate hashes and one signature, no key material");
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) throw new IOException("cannot replace " + file.getName());
        }

        /**
         * Loads each format's identity from {@code root} and compares it with this record. A value
         * that differs is a failed check; anything else that goes wrong propagates.
         */
        void verify(File root, Checks checks) throws Exception {
            for (String f : FORMATS) {
                LocalMfiAuthenticator auth = LocalMfiAuthenticator.load(new File(root, f));
                checks.check(Arrays.equals(sha256(auth.readCertificate(LocalMfiAuthenticator.MAX_FILE_LENGTH)), certificateHashes.get(f)),
                        f + ": certificate unchanged");
                checks.check(Arrays.equals(auth.signDigest(digest), signatures.get(f)), f + ": same deterministic signature as the earlier run");
            }
        }

        interface Checks {
            void check(boolean ok, String what);
        }

        /** The record exists but cannot be used; the message says why. */
        static final class Unusable extends Exception {
            Unusable(String message) {
                super(message);
            }
        }

        private static int processId(Properties p) throws Unusable {
            String v = required(p, "pid");
            if (v.isEmpty() || v.length() > 9) throw new Unusable("pid \"" + v + "\" is not a process ID");
            for (int i = 0; i < v.length(); i++) {
                if (v.charAt(i) < '0' || v.charAt(i) > '9') throw new Unusable("pid \"" + v + "\" is not a process ID");
            }
            int pid = Integer.parseInt(v);
            if (pid == 0) throw new Unusable("pid 0 is not a process ID");
            return pid;
        }

        /** Exactly {@code bytes * 2} digits 0-9, a-f or A-F; nothing else, not even other scripts' digits. */
        private static byte[] hex(Properties p, String key, int bytes) throws Unusable {
            String v = required(p, key);
            if (v.length() != 2 * bytes) throw new Unusable(key + " has " + v.length() + " hex digits, expected " + 2 * bytes);
            byte[] out = new byte[bytes];
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (d < 0) throw new Unusable(key + " has a character that is not a hex digit at position " + i);
                out[i / 2] = (byte) (out[i / 2] << 4 | d);
            }
            return out;
        }

        private static String required(Properties p, String key) throws Unusable {
            String v = p.getProperty(key);
            if (v == null) throw new Unusable("no " + key);
            return v;
        }
    }
}
