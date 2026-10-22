package me.ri3d.openauto.carplay.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import me.ri3d.openauto.carplay.auth.MfiAuthenticator;
import me.ri3d.openauto.carplay.iap2.BluetoothProfile;
import me.ri3d.openauto.carplay.iap2.ControlSession;
import me.ri3d.openauto.carplay.iap2.CsmMessage;
import me.ri3d.openauto.carplay.iap2.Iap2Connection;
import me.ri3d.openauto.carplay.iap2.Iap2Stream;
import me.ri3d.openauto.carplay.iap2.IdentificationPayload;

/**
 * One user-started iAP2 handshake with one phone, with a SYNTHETIC accessory identity: a connected
 * RFCOMM socket from the endpoint, then the Bluetooth profile's link and control session on it
 * ({@link Iap2Stream}) until the phone ends it, Cancel, or the time limit. The socket, the link,
 * the session, the messages the phone sent after the handshake and the way it ended are reported
 * apart. An iPhone is expected to refuse a certificate Apple did not issue; what this check is for
 * is everything before that verdict: the echo, the SYN+ACK, which exchange the phone starts, and
 * what it rejects. The service UUIDs and the detection bytes stay hypotheses until a phone answers.
 */
final class HandshakeCheck {
    /** Without progress for this long the control session gives up; the limit of the whole check is the caller's. */
    static final long SESSION_TIMEOUT_MS = 10_000;

    private final RfcommCheck.Endpoint endpoint;
    private final MfiAuthenticator authenticator;
    private final IdentificationPayload identification;
    private volatile Iap2Stream stream;
    private volatile String stoppedBy;

    HandshakeCheck(RfcommCheck.Endpoint endpoint, MfiAuthenticator authenticator, IdentificationPayload identification) {
        this.endpoint = endpoint;
        this.authenticator = authenticator;
        this.identification = identification;
    }

    /** Any thread: ends the link (with RST if it is up) and closes the endpoint. */
    void cancel() {
        stop("Cancel");
    }

    private void stop(String why) {
        synchronized (this) {
            if (stoppedBy == null) stoppedBy = why;
        }
        Iap2Stream s = stream;
        if (s != null) s.stop();
        endpoint.close();
    }

    /**
     * Blocks for at most about {@code limitMs}; one line per observation. The timer below guards the
     * connect only; once the link runs, the stream's own limit (what is left of {@code limitMs})
     * ends it, so the two never race.
     */
    List<String> run(final long limitMs) {
        List<String> out = new ArrayList<>();
        Timer deadline = new Timer("handshake-check-limit", true);
        deadline.schedule(new TimerTask() {
            @Override
            public void run() {
                stop("the " + limitMs + " ms limit");
            }
        }, limitMs);
        long start = System.nanoTime();
        try {
            String peer;
            try {
                if (stoppedBy != null) throw new IOException("not started");
                peer = endpoint.open();
            } catch (IOException | SecurityException e) {
                out.add("socket: not connected after " + since(start) + " ms: " + why(e));
                out.add("link: not started");
                return note(out);
            }
            deadline.cancel();
            out.add("socket: connected after " + since(start) + " ms (" + peer + ")");
            Iap2Stream s;
            try {
                s = new Iap2Stream(endpoint.input(), endpoint.output(), BluetoothProfile.linkProposal(), BluetoothProfile.DETECT,
                        authenticator, identification, SESSION_TIMEOUT_MS);
            } catch (IOException e) {
                out.add("link: not started: " + why(e));
                return note(out);
            }
            stream = s;
            if (stoppedBy != null) {
                out.add("link: not started: stopped by " + stoppedBy);
                return note(out);
            }
            long linkStart = System.nanoTime();
            String why = s.run(Math.max(1, limitMs - since(start)));
            if (why.startsWith("time limit")) {
                synchronized (this) { // the endpoint is closed in finally, after the report; closing it here would add the reader's own end to it
                    if (stoppedBy == null) stoppedBy = "the " + limitMs + " ms limit";
                }
            }
            Iap2Connection c = s.connection();
            String by = stoppedBy;
            out.add("link: " + c.link().state() + " after " + since(linkStart) + " ms; ended because " + why + (by != null && why.equals("stopped") ? " by " + by : ""));
            if (c.link().negotiated() != null) out.add("negotiated: " + c.link().negotiated());
            out.add("link packets: " + c.link().stats());
            ControlSession session = c.session();
            if (session == null) {
                out.add("session: none, the link never synchronized");
            } else {
                out.add("session: " + session + (session.firstExchange() == null ? "; the phone started no exchange" : "; the phone started with " + session.firstExchange()));
                out.add(s.wasIdentifiedAndAuthenticated()
                        ? "verdict: identification accepted and authentication succeeded with the SYNTHETIC identity. Unexpected from an iPhone, "
                        + "which should refuse a certificate Apple did not issue; a test result, not certification and not CarPlay"
                        : "verdict: not identified and authenticated (expected with a SYNTHETIC identity; see the session line for how far it got)");
            }
            if (c.unhandled.isEmpty()) {
                out.add("after the handshake: no other messages from the phone");
            } else {
                StringBuilder sb = new StringBuilder("after the handshake: " + c.unhandled.size() + " message(s) from the phone, not answered:");
                for (CsmMessage m : c.unhandled) sb.append(' ').append(m);
                out.add(sb.toString());
            }
            if (s.endOfInput() != null) out.add("input: " + s.endOfInput());
            return note(out);
        } finally {
            deadline.cancel();
            endpoint.close();
        }
    }

    private List<String> note(List<String> out) {
        String by = stoppedBy;
        if (by != null && by.startsWith("the ")) {
            out.add("note: stopped at the time limit; this is no evidence that the phone or the radio cannot support CarPlay");
        }
        return out;
    }

    private String why(Exception e) {
        String by = stoppedBy;
        return by != null ? "stopped by " + by + " (" + e + ")" : e.toString();
    }

    private static long since(long start) {
        return (System.nanoTime() - start) / 1000000;
    }
}
