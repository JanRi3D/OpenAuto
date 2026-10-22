package me.ri3d.openauto.carplay.iap2;

import java.util.ArrayList;
import java.util.List;

import me.ri3d.openauto.carplay.auth.MfiAuthenticator;

/**
 * One iAP2 connection over a transport: the link layer, and a new control session for every
 * synchronized link, so nothing a phone confirmed on an earlier link counts on the next. A failed
 * session takes the link down with RST; reconnecting means {@link #start} again.
 *
 * <p>Not thread-safe, like the link: call every method from one thread.
 */
public final class Iap2Connection implements Iap2LinkLayer.Upper {
    private final Iap2LinkLayer link;
    private final MfiAuthenticator authenticator;
    private final IdentificationPayload identification;
    private final long sessionTimeoutMillis;
    private ControlSession session;
    /** Messages for the layer above the control session, which does not exist yet; counted and dropped. */
    int unhandledMessages;
    /** The first {@link #MAX_RECORDED_UNHANDLED} of them, kept to see what a phone sends after the handshake. */
    public final List<CsmMessage> unhandled = new ArrayList<>(0);
    public static final int MAX_RECORDED_UNHANDLED = 16;

    /** @param detect the profile's detection bytes, or null to start with the SYN */
    public Iap2Connection(Iap2LinkLayer.Transport transport, Iap2LinkLayer.Parameters proposal, byte[] detect, MfiAuthenticator authenticator,
                          IdentificationPayload identification, long sessionTimeoutMillis) {
        this.link = new Iap2LinkLayer(transport, this, proposal, detect);
        this.authenticator = authenticator;
        this.identification = identification;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
    }

    /** Every synchronized link gets a new session in {@link #linkUp}; until then the last one stays readable. */
    public void start(long now, int initialSequence) {
        link.start(now, initialSequence);
    }

    public void receive(byte[] b, int off, int len, long now) {
        link.receive(b, off, len, now);
        closeIfFailed();
    }

    public void poll(long now) {
        link.poll(now);
        if (session != null) session.poll(now);
        closeIfFailed();
    }

    public void transportClosed() {
        link.transportClosed();
    }

    public Iap2LinkLayer link() {
        return link;
    }

    /** The session of the current or last link; null before the first synchronization. */
    public ControlSession session() {
        return session;
    }

    /**
     * The link is up and its session completed identification and authentication. With a simulated
     * peer this is a test result; it is not iPhone acceptance, and CarPlay is not implemented.
     */
    public boolean isIdentifiedAndAuthenticated() {
        return link.state() == Iap2LinkLayer.State.UP && session != null && session.isIdentifiedAndAuthenticated();
    }

    @Override
    public void linkUp(Iap2LinkLayer.Parameters negotiated, long now) {
        session = new ControlSession(link, authenticator, identification, sessionTimeoutMillis, now);
    }

    @Override
    public void controlBytes(byte[] data, int off, int len, long now) {
        if (session == null) return;
        for (CsmMessage m : session.receive(data, off, len, now)) {
            unhandledMessages++;
            if (unhandled.size() < MAX_RECORDED_UNHANDLED) unhandled.add(m);
        }
    }

    @Override
    public void linkDown(Iap2LinkLayer.Down reason, String detail) {
        if (session != null) session.linkLost();
    }

    /** Only an up link has a session of its own; before that, the session is still the last link's. */
    private void closeIfFailed() {
        if (link.state() == Iap2LinkLayer.State.UP && session != null && session.failure() != null) link.close();
    }
}
