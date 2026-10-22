package me.ri3d.openauto.carplay.iap2;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import me.ri3d.openauto.carplay.auth.MfiAuthenticator;
import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;

/**
 * The accessory's side of iAP2 identification and authentication on one control session, which
 * lives exactly as long as one synchronized link. The phone starts both exchanges. Each message is
 * accepted only in the state its exchange has reached, and {@link #isIdentifiedAndAuthenticated}
 * holds only after this session's own IdentificationAccepted and AuthenticationSucceeded. Sending a
 * signature is not success, nothing carries over from an earlier session, and none of this says
 * anything about CarPlay, which is not implemented.
 *
 * <p>Provisional: either exchange may start first. No order is known to be mandatory and none has
 * been observed with an iPhone; {@link #firstExchange} records what the phone did.
 *
 * <p>Failures are final and clear the progress state: a malformed or unexpected message, an
 * authentication failure, a timeout or a lost link ends the session, and nothing is retried with
 * the same credentials. A rejected identification is answered with a correction from the payload,
 * but only when the rejection names parameters this accessory sent, the payload supports a
 * correction for each, and the correction changes exactly those parameters (compared by content, so
 * reordering repeated parameters or the parts of a group is no change).
 *
 * <p>Time comes from the caller ({@code now}, in milliseconds on any monotonic clock); without
 * progress for {@code timeoutMillis} before identification and authentication complete,
 * {@link #poll} ends the session.
 */
public final class ControlSession {
    public static final int START_IDENTIFICATION = 0x1D00;
    public static final int IDENTIFICATION_INFORMATION = 0x1D01;
    public static final int IDENTIFICATION_ACCEPTED = 0x1D02;
    public static final int IDENTIFICATION_REJECTED = 0x1D03;
    public static final int REQUEST_AUTHENTICATION_CERTIFICATE = 0xAA00;
    public static final int AUTHENTICATION_CERTIFICATE = 0xAA01;
    public static final int REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE = 0xAA02;
    public static final int AUTHENTICATION_RESPONSE = 0xAA03;
    public static final int AUTHENTICATION_FAILED = 0xAA04;
    public static final int AUTHENTICATION_SUCCEEDED = 0xAA05;
    /** Identification information sent in total, first attempt included. A local limit, not a protocol value. */
    public static final int MAX_IDENTIFICATION_ATTEMPTS = 3;
    private static final Charset BYTES = Charset.forName("ISO-8859-1");

    public enum Identification { NOT_STARTED, INFORMATION_SENT, ACCEPTED }

    public enum Authentication { NOT_STARTED, CERTIFICATE_SENT, RESPONSE_SENT, SUCCEEDED }

    /** Which exchange the phone started first; recorded to settle the order on real hardware. */
    public enum Exchange { IDENTIFICATION, AUTHENTICATION }

    public enum Failure {
        /** Bytes that are not a valid control session message, or a required parameter missing or repeated. */
        MALFORMED,
        /** A message that does not fit the current step of its exchange, or an unknown message before both complete. */
        UNEXPECTED_MESSAGE,
        /** Rejected without an understood reason, without a supported correction, or too often. */
        IDENTIFICATION_REJECTED,
        AUTHENTICATION_FAILED,
        /** A challenge the signer refuses (not 32 bytes). */
        BAD_CHALLENGE,
        /** The certificate or signature could not be produced. */
        IDENTITY_ERROR,
        LINK_ERROR,
        TIMEOUT,
        DISCONNECTED
    }

    private final Iap2Link link;
    private final MfiAuthenticator authenticator;
    private final long timeoutMillis;
    private final CsmMessage.Reader reader = new CsmMessage.Reader();
    private IdentificationPayload payload;
    private Identification identification = Identification.NOT_STARTED;
    private Authentication authentication = Authentication.NOT_STARTED;
    private Exchange firstExchange;
    private int identificationAttempts;
    private CsmMessage rejection;
    private Failure failure;
    private String failureDetail;
    private long deadline;

    public ControlSession(Iap2Link link, MfiAuthenticator authenticator, IdentificationPayload payload, long timeoutMillis, long now) {
        if (payload.message().id != IDENTIFICATION_INFORMATION) throw new IllegalArgumentException("not IdentificationInformation: " + payload.message());
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        this.link = link;
        this.authenticator = authenticator;
        this.payload = payload;
        this.timeoutMillis = timeoutMillis;
        this.deadline = now + timeoutMillis;
    }

    /**
     * Control session bytes from the link. Returns the messages meant for the layer above, which
     * exist only once identification and authentication are complete; before that every message
     * belongs to one of them.
     */
    public synchronized List<CsmMessage> receive(byte[] data, int off, int len, long now) {
        if (failure != null) return Collections.emptyList();
        List<CsmMessage> above = new ArrayList<>(0);
        try {
            for (CsmMessage m : reader.feed(data, off, len)) {
                if (isIdentifiedAndAuthenticated() && !isHandshake(m.id)) {
                    above.add(m);
                } else {
                    handle(m);
                    deadline = now + timeoutMillis;
                }
            }
        } catch (ProtocolException e) {
            fail(Failure.MALFORMED, e.getMessage());
        } catch (Stop stop) {
            fail(stop.failure, stop.getMessage());
        }
        return failure == null ? above : Collections.<CsmMessage>emptyList();
    }

    /** Ends the session if identification and authentication are not complete and nothing moved them on within the timeout. */
    public synchronized void poll(long now) {
        if (failure == null && !isIdentifiedAndAuthenticated() && now >= deadline) {
            fail(Failure.TIMEOUT, "no progress for " + timeoutMillis + " ms");
        }
    }

    /** The link below is gone; the session ends, also when it was complete. */
    public synchronized void linkLost() {
        if (failure == null) {
            fail(Failure.DISCONNECTED, "link lost" + (reader.pending() > 0 ? " with " + reader.pending() + " bytes of a message pending" : ""));
        }
    }

    /**
     * True after this session's IdentificationAccepted and AuthenticationSucceeded. With a simulated
     * peer this is a test result; it is never evidence of CarPlay, which is not implemented.
     */
    public synchronized boolean isIdentifiedAndAuthenticated() {
        return failure == null && identification == Identification.ACCEPTED && authentication == Authentication.SUCCEEDED;
    }

    public synchronized Identification identification() {
        return identification;
    }

    public synchronized Authentication authentication() {
        return authentication;
    }

    /** Null until the phone starts an exchange. */
    public synchronized Exchange firstExchange() {
        return firstExchange;
    }

    /** The identification currently in force: the original or its latest correction. */
    public synchronized IdentificationPayload payload() {
        return payload;
    }

    /** Null while the session is alive. */
    public synchronized Failure failure() {
        return failure;
    }

    /** Why the session failed and how far it had got. */
    public synchronized String failureDetail() {
        return failureDetail;
    }

    /** The phone's last IdentificationRejected while the session is alive; its parameter IDs name what it refused. */
    public synchronized CsmMessage rejection() {
        return rejection;
    }

    private void handle(CsmMessage m) throws Stop, ProtocolException {
        switch (m.id) {
            case START_IDENTIFICATION:
                expect(m, identification == Identification.NOT_STARTED);
                if (firstExchange == null) firstExchange = Exchange.IDENTIFICATION;
                sendIdentification();
                break;
            case IDENTIFICATION_ACCEPTED:
                expect(m, identification == Identification.INFORMATION_SENT);
                identification = Identification.ACCEPTED;
                break;
            case IDENTIFICATION_REJECTED:
                expect(m, identification == Identification.INFORMATION_SENT);
                rejection = m;
                correctIdentification(m);
                break;
            case REQUEST_AUTHENTICATION_CERTIFICATE:
                expect(m, authentication == Authentication.NOT_STARTED);
                if (firstExchange == null) firstExchange = Exchange.AUTHENTICATION;
                byte[] certificate;
                try {
                    certificate = authenticator.readCertificate(CsmMessage.MAX_PARAMETER_VALUE);
                } catch (IllegalArgumentException e) {
                    throw new Stop(Failure.IDENTITY_ERROR, "certificate does not fit a control session message");
                }
                send(new CsmMessage(AUTHENTICATION_CERTIFICATE, new Parameter(0, certificate)));
                authentication = Authentication.CERTIFICATE_SENT;
                break;
            case REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE:
                expect(m, authentication == Authentication.CERTIFICATE_SENT);
                byte[] challenge = m.single(0);
                if (challenge == null) throw new ProtocolException("challenge request without parameter 0");
                byte[] signature;
                try {
                    signature = authenticator.signDigest(challenge);
                } catch (IllegalArgumentException e) {
                    throw new Stop(Failure.BAD_CHALLENGE, "challenge of " + challenge.length + " bytes refused by the signer");
                } catch (GeneralSecurityException e) {
                    throw new Stop(Failure.IDENTITY_ERROR, "signing failed: " + e.getMessage());
                }
                send(new CsmMessage(AUTHENTICATION_RESPONSE, new Parameter(0, signature)));
                authentication = Authentication.RESPONSE_SENT; // not success: only the phone can say that
                break;
            case AUTHENTICATION_SUCCEEDED:
                expect(m, authentication == Authentication.RESPONSE_SENT);
                authentication = Authentication.SUCCEEDED;
                break;
            case AUTHENTICATION_FAILED:
                expect(m, authentication == Authentication.CERTIFICATE_SENT || authentication == Authentication.RESPONSE_SENT);
                throw new Stop(Failure.AUTHENTICATION_FAILED, "the phone refused the accessory authentication");
            default:
                throw new Stop(Failure.UNEXPECTED_MESSAGE, "message " + m + " before identification and authentication complete");
        }
    }

    /**
     * Understood: the rejection names parameters this accessory sent. Supported: the payload has a
     * correction for each. Meaningful: the correction changes exactly those parameters by content.
     */
    private void correctIdentification(CsmMessage m) throws Stop {
        Set<Integer> rejected = new TreeSet<>();
        for (Parameter p : m.parameters()) rejected.add(p.id);
        if (rejected.isEmpty()) throw new Stop(Failure.IDENTIFICATION_REJECTED, "rejection names no parameter, nothing to correct");
        Set<Integer> sent = new TreeSet<>();
        for (Parameter p : payload.message().parameters()) sent.add(p.id);
        for (int id : rejected) {
            if (!sent.contains(id)) throw new Stop(Failure.IDENTIFICATION_REJECTED, "rejection names parameter " + id + ", which this accessory did not send");
        }
        if (identificationAttempts >= MAX_IDENTIFICATION_ATTEMPTS) {
            throw new Stop(Failure.IDENTIFICATION_REJECTED, "identification rejected " + identificationAttempts + " times: " + m);
        }
        IdentificationPayload corrected;
        try {
            corrected = payload.correctedFor(Collections.unmodifiableSet(rejected));
        } catch (IdentificationPayload.Uncorrectable e) {
            throw new Stop(Failure.IDENTIFICATION_REJECTED, e.getMessage());
        }
        Set<Integer> changed = changedIds(payload.message(), corrected.message(), payload.groupIds());
        if (!changed.equals(rejected)) {
            throw new Stop(Failure.IDENTIFICATION_REJECTED, "the correction changes parameters " + changed + " instead of exactly the rejected " + rejected);
        }
        payload = corrected;
        sendIdentification();
    }

    /**
     * Top-level parameter IDs whose content differs between two messages. Repeated parameters
     * compare as multisets, and a group as the multiset of its sub-parameters, so order alone never
     * counts. A group value that does not parse compares byte for byte.
     */
    static Set<Integer> changedIds(CsmMessage a, CsmMessage b, Set<Integer> groupIds) {
        Map<Integer, List<String>> x = content(a, groupIds), y = content(b, groupIds);
        Set<Integer> ids = new TreeSet<>(x.keySet());
        ids.addAll(y.keySet());
        Set<Integer> changed = new TreeSet<>();
        for (int id : ids) {
            List<String> u = x.get(id), v = y.get(id);
            if (u == null || v == null || !u.equals(v)) changed.add(id);
        }
        return changed;
    }

    private static Map<Integer, List<String>> content(CsmMessage m, Set<Integer> groupIds) {
        Map<Integer, List<String>> out = new TreeMap<>();
        for (Parameter p : m.parameters()) {
            List<String> values = out.get(p.id);
            if (values == null) out.put(p.id, values = new ArrayList<>());
            values.add(groupIds.contains(p.id) ? group(p.value()) : "=" + new String(p.value(), BYTES));
        }
        for (List<String> values : out.values()) Collections.sort(values);
        return out;
    }

    /**
     * A group as the sorted multiset of its sub-parameters, written back in the length-prefixed
     * parameter encoding, which no two different multisets share. (Joining them as text did:
     * {@code (0,"a"),(1,"b")} and {@code (0,"a, 1=b")} looked the same.)
     */
    private static String group(byte[] value) {
        try {
            List<Parameter> parts = new ArrayList<>(CsmMessage.decodeParameters(value, 0, value.length));
            Collections.sort(parts, PARAMETER_ORDER);
            return "{" + new String(CsmMessage.encodeParameters(parts), BYTES);
        } catch (ProtocolException e) {
            return "=" + new String(value, BYTES);
        }
    }

    /** By ID, then by value as unsigned bytes, then by length. */
    private static final Comparator<Parameter> PARAMETER_ORDER = (a, b) -> {
        if (a.id != b.id) return a.id - b.id;
        byte[] x = a.value(), y = b.value();
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) return d;
        }
        return x.length - y.length;
    };

    private void sendIdentification() throws Stop {
        send(payload.message());
        identificationAttempts++;
        identification = Identification.INFORMATION_SENT;
    }

    private void send(CsmMessage m) throws Stop {
        try {
            link.sendControl(m.encode());
        } catch (IOException | IllegalArgumentException e) {
            throw new Stop(Failure.LINK_ERROR, "cannot send " + m + ": " + e.getMessage());
        }
    }

    private void expect(CsmMessage m, boolean inOrder) throws Stop {
        if (!inOrder) throw new Stop(Failure.UNEXPECTED_MESSAGE, "message " + m + " out of order");
    }

    /** Keeps the first failure, records how far the session had got, then clears that progress. */
    private void fail(Failure f, String detail) {
        if (failure != null) return;
        failure = f;
        failureDetail = detail + "; " + progress();
        identification = Identification.NOT_STARTED;
        authentication = Authentication.NOT_STARTED;
        rejection = null;
    }

    private String progress() {
        return "identification " + identification + ", authentication " + authentication;
    }

    private static boolean isHandshake(int id) {
        return (id >= START_IDENTIFICATION && id <= IDENTIFICATION_REJECTED)
                || (id >= REQUEST_AUTHENTICATION_CERTIFICATE && id <= AUTHENTICATION_SUCCEEDED);
    }

    /** Ends the session from inside a message handler. */
    private static final class Stop extends Exception {
        final Failure failure;

        Stop(Failure failure, String detail) {
            super(detail);
            this.failure = failure;
        }
    }

    @Override
    public synchronized String toString() {
        return String.format(Locale.US, "iAP2 control session: %s%s", progress(), failure == null ? "" : ", failed " + failure + ": " + failureDetail);
    }
}
