package me.ri3d.openauto.carplay.iap2;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The accessory's end of the iAP2 link: optional detection, synchronization, packet framing,
 * checksums, acknowledgement and retransmission, carrying the control session only. The rules for
 * every synchronization field and each start-up case, with sources: docs/iap2-reference.md §2-§3.
 *
 * <p>A packet is 0xFF 0x5A, a 16-bit length of the whole packet, control bits (SYN, ACK, EAK, RST,
 * SLP), sequence number, acknowledgement number, session ID and a header checksum; then, if there is
 * a payload, the payload and its checksum. Each checksum makes the byte sum of what it covers zero.
 * Sequence numbers are 8 bits and wrap. Packets with a payload take the next number;
 * acknowledgement-only packets repeat the last one used.
 *
 * <p>Start-up: the accessory sends the detection bytes (when the profile has them) until the phone
 * echoes them, then its SYN, then answers the phone's SYN+ACK with ACK, or with RST if the answer
 * cannot be used. Explicitly unsupported, each ending the link with its own reason: a phone that
 * starts synchronization itself, both sides sending SYN at once, ZeroACK mode, and a counter-proposal
 * that renames or re-versions a session. A SYN from the phone on a synchronized link ends it as
 * {@link Down#RESYNC_REQUESTED}; the owner starts a new link. Recovery is a chosen subset: an
 * incoming EAK counts for its acknowledgement number but its list is not used (unacknowledged
 * packets are all resent), and this side drops out-of-order packets instead of keeping them, so it
 * never sends EAK. The SYN+ACK field rules are provisional (docs/iap2-reference.md §2).
 *
 * <p>Not thread-safe: call every method from one thread, and do not call {@link #start} from inside
 * an {@link Upper} callback. Time comes from the caller; {@link #sendControl} times its packets with
 * the {@code now} of the latest {@link #receive} or {@link #poll}.
 */
public final class Iap2LinkLayer implements Iap2Link {
    public static final int SYN = 0x80, ACK = 0x40, EAK = 0x20, RST = 0x10, SLP = 0x08;
    public static final int HEADER_LENGTH = 9;
    /** A header, one payload byte and the payload checksum: the shortest packet that carries data. */
    public static final int MIN_PACKET_LENGTH = HEADER_LENGTH + 2;
    /** Unacknowledged packets in flight are kept below half the 8-bit sequence space, so old and new numbers never mix. */
    public static final int MAX_SEND_WINDOW = 127;
    /** Control session bytes allowed to wait for room in the send window. */
    public static final int MAX_QUEUED_BYTES = 2 * CsmMessage.MAX_LENGTH;
    public static final int CONTROL_SESSION_TYPE = 0;

    /** Where packets go: the RFCOMM (or other) byte stream. */
    public interface Transport {
        void write(byte[] packet) throws IOException;
    }

    /** What the link reports upwards. */
    public interface Upper {
        void linkUp(Parameters negotiated, long now);

        /** Control session bytes, in order, without gaps or repeats; one message may arrive in several calls. */
        void controlBytes(byte[] data, int off, int len, long now);

        void linkDown(Down reason, String detail);
    }

    public enum State { IDLE, DETECTING, SYNCHRONIZING, UP, DOWN }

    public enum Down {
        /** No echo of the detection bytes after the proposal's retransmissions, or a different answer (a device without iAP2). */
        DETECTION_FAILED,
        /** No usable SYN+ACK after the proposal's retransmissions. */
        SYNC_TIMEOUT,
        /** The phone's SYN+ACK holds values this side cannot work with; RST sent. */
        PARAMETERS_REFUSED,
        /** A valid case this side does not implement; RST sent where a link was being set up. */
        UNSUPPORTED,
        /** The phone sent SYN on a synchronized link; start a new link. */
        RESYNC_REQUESTED,
        /** A packet stayed unacknowledged after the negotiated retransmissions. */
        RETRANSMISSION_LIMIT,
        RESET_BY_PHONE,
        PHONE_SLEEP,
        /** A packet that makes no sense here: a second, different SYN+ACK, or data for no open session. */
        PROTOCOL_ERROR,
        TRANSPORT_ERROR,
        DISCONNECTED,
        CLOSED
    }

    private final Transport transport;
    private final Upper upper;
    private final Parameters proposal;
    private final byte[] detect;
    private final byte[] in = new byte[0xFFFF];
    private int inLen;

    private State state = State.IDLE;
    private Down downReason;
    private String downDetail;
    private Parameters negotiated;
    private int controlId, sendWindow, sendPacketLength, incomingLimit, ackThreshold;
    private int synAckSeq = -1;
    private byte[] synAckPayload;
    private int initialSeq, sendSeq, recvSeq;
    private long startDeadline;
    private int startSends;
    private final ArrayDeque<Outgoing> unacked = new ArrayDeque<>();
    private final ArrayDeque<byte[]> waiting = new ArrayDeque<>();
    private int waitingBytes;
    private boolean ackOwed;
    private int receivedSinceAck;
    private long ackDeadline;
    private long lastNow;
    private String lastDetectAnswer;

    int corruptPackets, duplicatePackets, outOfOrderPackets, retransmittedPackets, strayPackets, eakPackets;

    /** @param detect bytes to send before the SYN until the phone echoes them; null to start with the SYN */
    public Iap2LinkLayer(Transport transport, Upper upper, Parameters proposal, byte[] detect) {
        Refusal invalid = checkAnswer(proposal, proposal);
        if (invalid != null) throw new IllegalArgumentException("unusable proposal: " + invalid.reason);
        this.transport = transport;
        this.upper = upper;
        this.proposal = proposal;
        this.detect = detect == null ? null : detect.clone();
    }

    /** Starts (or, after the link went down, restarts) the link with fresh state. */
    public void start(long now, int initialSequence) {
        if (active()) throw new IllegalStateException("link is " + state);
        unacked.clear();
        waiting.clear();
        waitingBytes = 0;
        inLen = 0;
        ackOwed = false;
        receivedSinceAck = 0;
        negotiated = null;
        controlId = -1; // matches no session byte until a SYN+ACK is accepted
        recvSeq = 0;
        synAckSeq = -1;
        synAckPayload = null;
        downReason = null;
        downDetail = null;
        initialSeq = sendSeq = initialSequence & 0xFF;
        startSends = 0;
        lastDetectAnswer = null;
        lastNow = now;
        if (detect != null) {
            state = State.DETECTING;
            sendDetect(now);
        } else {
            state = State.SYNCHRONIZING;
            sendSyn(now);
        }
    }

    /** Bytes from the transport, split or joined at any point. */
    public void receive(byte[] b, int off, int len, long now) {
        lastNow = now;
        while (len > 0 && active()) {
            int n = Math.min(len, in.length - inLen);
            System.arraycopy(b, off, in, inLen, n);
            inLen += n;
            off += n;
            len -= n;
            if (state == State.DETECTING) awaitEcho(now);
            if (state == State.SYNCHRONIZING || state == State.UP) extractPackets(now);
        }
        if (state == State.UP && ackOwed && receivedSinceAck >= ackThreshold) sendAck();
    }

    /** Detection and SYN repeats, retransmissions and delayed acknowledgements; call every few milliseconds while active. */
    public void poll(long now) {
        lastNow = now;
        if (state == State.DETECTING || state == State.SYNCHRONIZING) {
            if (now >= startDeadline) {
                if (startSends > proposal.maxRetransmissions) {
                    if (state == State.DETECTING) {
                        down(Down.DETECTION_FAILED, "no echo of the detection bytes after " + startSends + " tries"
                                + (lastDetectAnswer == null ? "" : "; last answer " + lastDetectAnswer + " (a device without iAP2?)"));
                    } else {
                        down(Down.SYNC_TIMEOUT, "no usable SYN+ACK after " + startSends + " SYN packets");
                    }
                } else if (state == State.DETECTING) {
                    sendDetect(now);
                } else {
                    sendSyn(now);
                }
            }
            return;
        }
        if (state != State.UP) return;
        Outgoing oldest = unacked.peekFirst();
        if (oldest != null && now - oldest.sentAt >= negotiated.retransmissionTimeout) {
            if (oldest.sends > negotiated.maxRetransmissions) {
                down(Down.RETRANSMISSION_LIMIT, "packet " + oldest.seq + " unacknowledged after " + (oldest.sends - 1) + " retransmissions");
                return;
            }
            for (Outgoing o : unacked) {
                retransmittedPackets++;
                if (!transmit(o, now)) return;
            }
        }
        if (ackOwed && now >= ackDeadline) sendAck();
    }

    @Override
    public void sendControl(byte[] message) throws IOException {
        if (message.length > CsmMessage.MAX_LENGTH) {
            throw new IllegalArgumentException("a control session message has at most " + CsmMessage.MAX_LENGTH + " bytes, not " + message.length);
        }
        if (state != State.UP) throw new IOException("link is " + state);
        if (waitingBytes + message.length > MAX_QUEUED_BYTES) {
            throw new IOException("send queue full: " + waitingBytes + " bytes waiting, " + unacked.size() + " packets unacknowledged");
        }
        int room = sendPacketLength - HEADER_LENGTH - 1;
        for (int off = 0; off < message.length; off += room) {
            waiting.add(Arrays.copyOfRange(message, off, Math.min(message.length, off + room)));
        }
        waitingBytes += message.length;
        sendWaiting(lastNow);
        if (state != State.UP) throw new IOException("link went down: " + downDetail);
    }

    /** The transport is gone. */
    public void transportClosed() {
        down(Down.DISCONNECTED, "transport closed");
    }

    /** Ends the link from this side, telling the phone with RST once synchronization has begun. */
    public void close() {
        if (!active()) return;
        if (state != State.DETECTING) write(packet(RST, (sendSeq + 1) & 0xFF, recvSeq, 0, new byte[0]));
        down(Down.CLOSED, "closed by the accessory");
    }

    public State state() {
        return state;
    }

    /** The phone's accepted SYN+ACK values; null until the link is up. */
    public Parameters negotiated() {
        return negotiated;
    }

    /** Packets this side may have unacknowledged: the phone's maximum, at most {@link #MAX_SEND_WINDOW}. */
    public int sendWindow() {
        return sendWindow;
    }

    public Down downReason() {
        return downReason;
    }

    public String downDetail() {
        return downDetail;
    }

    public String stats() {
        return String.format(Locale.US, "corrupt %d, duplicate %d, out of order %d, retransmitted %d, stray %d, EAK %d",
                corruptPackets, duplicatePackets, outOfOrderPackets, retransmittedPackets, strayPackets, eakPackets);
    }

    private boolean active() {
        return state == State.DETECTING || state == State.SYNCHRONIZING || state == State.UP;
    }

    /**
     * Waits for the exact echo of the detection bytes. Anything else starting with 0xFF 0x55 is
     * remembered and skipped: one damaged echo must not end the link, and a device without iAP2
     * never echoes, so the retries running out decides.
     */
    private void awaitEcho(long now) {
        int pos = 0;
        while (true) {
            while (pos < inLen && in[pos] != (byte) 0xFF) pos++;
            if (inLen - pos < 2) break;
            if (in[pos + 1] == 0x5A && inLen - pos >= HEADER_LENGTH && (in[pos + 4] & SYN) != 0 && sum(in, pos, HEADER_LENGTH) == 0) {
                down(Down.UNSUPPORTED, "the phone sent SYN before answering detection: phone-initiated synchronization is not implemented");
                return;
            }
            if (in[pos + 1] != 0x55) {
                if (in[pos + 1] == 0x5A && inLen - pos < HEADER_LENGTH) break; // maybe a SYN, wait for its header
                pos++;
                continue;
            }
            if (inLen - pos < detect.length) break;
            if (!Arrays.equals(Arrays.copyOfRange(in, pos, pos + detect.length), detect)) {
                lastDetectAnswer = hex(in, pos, detect.length);
                pos += 2;
                continue;
            }
            pos += detect.length;
            state = State.SYNCHRONIZING;
            startSends = 0;
            sendSyn(now);
            break;
        }
        if (!active()) {
            inLen = 0;
            return;
        }
        System.arraycopy(in, pos, in, 0, inLen - pos);
        inLen -= pos;
    }

    /** Takes every complete packet out of the buffer; skips bytes that cannot start a valid packet. */
    private void extractPackets(long now) {
        int pos = 0;
        while (state == State.SYNCHRONIZING || state == State.UP) {
            while (pos < inLen && in[pos] != (byte) 0xFF) pos++;
            if (inLen - pos < 2) break;
            if (in[pos + 1] != 0x5A) {
                pos++; // a repeated detection echo, or noise
                continue;
            }
            if (inLen - pos < HEADER_LENGTH) break;
            int length = u16(in, pos + 2);
            int limit = state == State.UP ? incomingLimit : proposal.maxPacketLength;
            if (sum(in, pos, HEADER_LENGTH) != 0 || length < HEADER_LENGTH || length == HEADER_LENGTH + 1 || length > limit) {
                corruptPackets++;
                pos++; // a damaged header says nothing reliable about where the next packet starts
                continue;
            }
            if (inLen - pos < length) break;
            if (length > HEADER_LENGTH && sum(in, pos + HEADER_LENGTH, length - HEADER_LENGTH) != 0) {
                corruptPackets++; // the sender will retransmit it
                pos += length;
                continue;
            }
            byte[] payload = length > HEADER_LENGTH ? Arrays.copyOfRange(in, pos + HEADER_LENGTH, pos + length - 1) : new byte[0];
            int control = in[pos + 4] & 0xFF, seq = in[pos + 5] & 0xFF, ack = in[pos + 6] & 0xFF, session = in[pos + 7] & 0xFF;
            pos += length;
            handlePacket(control, seq, ack, session, payload, now);
        }
        if (!active()) {
            inLen = 0;
            return;
        }
        System.arraycopy(in, pos, in, 0, inLen - pos);
        inLen -= pos;
    }

    private void handlePacket(int control, int seq, int ack, int session, byte[] payload, long now) {
        if ((control & RST) != 0) {
            down(Down.RESET_BY_PHONE, "RST from the phone");
            return;
        }
        if ((control & SLP) != 0) {
            down(Down.PHONE_SLEEP, "SLP from the phone");
            return;
        }
        if ((control & SYN) != 0) {
            handleSyn(control, seq, ack, payload, now);
            return;
        }
        if (state != State.UP) {
            strayPackets++; // left over from an earlier link
            return;
        }
        if ((control & ACK) != 0) acknowledge(ack, now);
        if ((control & EAK) != 0) {
            eakPackets++; // its list of packets the phone holds out of order is not used: all unacknowledged ones are resent
            return;
        }
        if (payload.length == 0) return;
        if (session != controlId) {
            down(Down.PROTOCOL_ERROR, "payload for session " + session + ", only control session " + controlId + " is open");
            return;
        }
        if (seq != ((recvSeq + 1) & 0xFF)) {
            if (((recvSeq - seq) & 0xFF) < 128) duplicatePackets++;
            else outOfOrderPackets++;
            sendAck(); // tells the phone again what arrived in order
            return;
        }
        recvSeq = seq;
        if (!ackOwed) ackDeadline = now + negotiated.cumulativeAckTimeout;
        ackOwed = true;
        receivedSinceAck++;
        upper.controlBytes(payload, 0, payload.length, now);
    }

    private void handleSyn(int control, int seq, int ack, byte[] payload, long now) {
        if ((control & ACK) == 0) {
            if (state == State.UP) down(Down.RESYNC_REQUESTED, "SYN from the phone on a synchronized link");
            else refuse(new Refusal(true, "both sides sent SYN: simultaneous synchronization is not implemented"), seq);
            return;
        }
        if (state == State.UP) {
            if (seq == synAckSeq && Arrays.equals(payload, synAckPayload)) {
                duplicatePackets++; // our ACK of it was lost
                sendAck();
            } else {
                down(Down.PROTOCOL_ERROR, "a different SYN+ACK on a synchronized link");
            }
            return;
        }
        if (ack != initialSeq) {
            strayPackets++; // answers a SYN of an earlier link
            return;
        }
        Parameters answer;
        try {
            answer = Parameters.decode(payload);
        } catch (ProtocolException e) {
            refuse(new Refusal(false, "SYN+ACK payload: " + e.getMessage()), seq);
            return;
        }
        Refusal refusal = checkAnswer(proposal, answer);
        if (refusal != null) {
            refuse(refusal, seq);
            return;
        }
        negotiated = answer;
        controlId = proposal.sessionOfType(CONTROL_SESSION_TYPE).id;
        sendWindow = Math.min(answer.maxOutstanding, MAX_SEND_WINDOW);
        sendPacketLength = answer.maxPacketLength;
        incomingLimit = Math.max(proposal.maxPacketLength, answer.maxPacketLength);
        ackThreshold = Math.min(answer.maxCumulativeAcks, Math.min(proposal.maxOutstanding, answer.maxOutstanding));
        recvSeq = seq;
        synAckSeq = seq;
        synAckPayload = payload;
        state = State.UP;
        sendAck();
        if (state == State.UP) upper.linkUp(negotiated, now);
    }

    private void refuse(Refusal refusal, int phoneSeq) {
        write(packet(RST, (sendSeq + 1) & 0xFF, phoneSeq, 0, new byte[0]));
        down(refusal.unsupported ? Down.UNSUPPORTED : Down.PARAMETERS_REFUSED, refusal.reason);
    }

    private void acknowledge(int ack, long now) {
        Outgoing oldest = unacked.peekFirst();
        if (oldest == null) return;
        int n = ((ack - oldest.seq) & 0xFF) + 1;
        if (n > unacked.size()) return; // acknowledges nothing in flight: a repeat or a stale number
        for (int i = 0; i < n; i++) unacked.removeFirst();
        sendWaiting(now);
    }

    private void sendWaiting(long now) {
        while (state == State.UP && !waiting.isEmpty() && unacked.size() < sendWindow) {
            byte[] chunk = waiting.removeFirst();
            waitingBytes -= chunk.length;
            sendSeq = (sendSeq + 1) & 0xFF;
            Outgoing o = new Outgoing(sendSeq, chunk);
            unacked.addLast(o);
            if (!transmit(o, now)) return;
        }
    }

    private boolean transmit(Outgoing o, long now) {
        o.sentAt = now;
        o.sends++;
        boolean ok = write(packet(ACK, o.seq, recvSeq, controlId, o.payload));
        ackOwed = false;
        receivedSinceAck = 0;
        return ok;
    }

    private void sendAck() {
        write(packet(ACK, sendSeq, recvSeq, 0, new byte[0]));
        ackOwed = false;
        receivedSinceAck = 0;
    }

    private void sendDetect(long now) {
        startSends++;
        startDeadline = now + proposal.retransmissionTimeout;
        write(detect.clone());
    }

    private void sendSyn(long now) {
        startSends++;
        startDeadline = now + proposal.retransmissionTimeout;
        write(packet(SYN, initialSeq, 0, 0, proposal.encode()));
    }

    private boolean write(byte[] packet) {
        try {
            transport.write(packet);
            return true;
        } catch (IOException e) {
            down(Down.TRANSPORT_ERROR, "write failed: " + e.getMessage());
            return false;
        }
    }

    private void down(Down reason, String detail) {
        if (!active()) return;
        state = State.DOWN;
        downReason = reason;
        downDetail = detail;
        unacked.clear();
        waiting.clear();
        waitingBytes = 0;
        ackOwed = false;
        upper.linkDown(reason, detail);
    }

    /** Why a SYN+ACK cannot be used, and whether that is because it is invalid or only unsupported here. */
    static final class Refusal {
        final boolean unsupported;
        final String reason;

        Refusal(boolean unsupported, String reason) {
            this.unsupported = unsupported;
            this.reason = reason;
        }
    }

    /**
     * Whether the phone's SYN+ACK {@code theirs} can be used with this side's proposal {@code mine};
     * null if so. Field by field (reasons in docs/iap2-reference.md §2):
     * <ul>
     * <li>Link version: must be the proposed one; packet meaning depends on it.</li>
     * <li>Sessions: IDs non-zero and unique; each answered session must be a proposed one with the
     * same ID, type and version (a renamed or re-versioned session would be a counter-proposal,
     * which needs another SYN round: unsupported); exactly one control session.</li>
     * <li>Maximum outstanding packets and maximum received packet length: the phone's own receive
     * limits. They bound what this side sends, not what it proposed, so larger values are fine;
     * only values that would let nothing through are refused.</li>
     * <li>Retransmission and acknowledgement timing and counts: taken from the answer. All four
     * zero is ZeroACK mode, unsupported; otherwise the retransmission timeout must be positive and
     * longer than the acknowledgement timeout, or packets would be resent before their
     * acknowledgement is due. Zero retransmissions and zero cumulative acknowledgements are valid
     * choices and kept.</li>
     * </ul>
     */
    static Refusal checkAnswer(Parameters mine, Parameters theirs) {
        if (theirs.version != mine.version) return new Refusal(false, "link version " + theirs.version + ", proposed " + mine.version);
        boolean[] ids = new boolean[256];
        int controls = 0;
        for (SessionEntry s : theirs.sessions) {
            if (s.id == 0 || ids[s.id]) return new Refusal(false, "session ID " + s.id + " zero or repeated");
            ids[s.id] = true;
            SessionEntry proposed = mine.sessionWithId(s.id);
            if (proposed == null) {
                return new Refusal(true, "session " + s + " was not proposed" + (mine.sessionOfType(s.type) != null
                        ? " (a renamed session is a counter-proposal, which needs another SYN round)" : ""));
            }
            if (proposed.type != s.type || proposed.version != s.version) {
                return new Refusal(true, "session " + s.id + " answered as " + s + ", proposed " + proposed + " (counter-proposal)");
            }
            if (s.type == CONTROL_SESSION_TYPE) controls++;
        }
        if (controls != 1) return new Refusal(false, controls + " control sessions; identification and authentication need exactly one");
        if (theirs.maxOutstanding == 0) return new Refusal(false, "the phone accepts no unacknowledged packet, so nothing could be sent");
        if (theirs.maxPacketLength < MIN_PACKET_LENGTH) {
            return new Refusal(false, "the phone receives packets of at most " + theirs.maxPacketLength + " bytes, no room for a payload byte");
        }
        if (theirs.retransmissionTimeout == 0 && theirs.cumulativeAckTimeout == 0 && theirs.maxRetransmissions == 0 && theirs.maxCumulativeAcks == 0) {
            return new Refusal(true, "ZeroACK mode (no acknowledgements or retransmissions) is not implemented");
        }
        if (theirs.retransmissionTimeout == 0) return new Refusal(false, "retransmission timeout 0 outside ZeroACK mode");
        if (theirs.cumulativeAckTimeout >= theirs.retransmissionTimeout) {
            return new Refusal(false, "acknowledgement timeout " + theirs.cumulativeAckTimeout + " ms not shorter than the retransmission timeout "
                    + theirs.retransmissionTimeout + " ms");
        }
        return null;
    }

    /** One link packet, checksums included. */
    public static byte[] packet(int control, int seq, int ack, int session, byte[] payload) {
        int length = HEADER_LENGTH + (payload.length == 0 ? 0 : payload.length + 1);
        byte[] p = new byte[length];
        p[0] = (byte) 0xFF;
        p[1] = 0x5A;
        p[2] = (byte) (length >> 8);
        p[3] = (byte) length;
        p[4] = (byte) control;
        p[5] = (byte) seq;
        p[6] = (byte) ack;
        p[7] = (byte) session;
        p[8] = (byte) -sum(p, 0, HEADER_LENGTH - 1);
        if (payload.length > 0) {
            System.arraycopy(payload, 0, p, HEADER_LENGTH, payload.length);
            p[length - 1] = (byte) -sum(payload, 0, payload.length);
        }
        return p;
    }

    private static int sum(byte[] b, int off, int len) {
        int s = 0;
        for (int i = off; i < off + len; i++) s += b[i];
        return s & 0xFF;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        return sb.toString();
    }

    private static final class Outgoing {
        final int seq;
        final byte[] payload;
        long sentAt;
        int sends;

        Outgoing(int seq, byte[] payload) {
            this.seq = seq;
            this.payload = payload;
        }
    }

    /** The link synchronization payload: limits and timing, then the session table. */
    public static final class Parameters {
        public final int version, maxOutstanding, maxPacketLength, retransmissionTimeout, cumulativeAckTimeout, maxRetransmissions, maxCumulativeAcks;
        public final List<SessionEntry> sessions;

        public Parameters(int version, int maxOutstanding, int maxPacketLength, int retransmissionTimeoutMillis, int cumulativeAckTimeoutMillis,
                          int maxRetransmissions, int maxCumulativeAcks, SessionEntry... sessions) {
            this.version = u8(version);
            this.maxOutstanding = u8(maxOutstanding);
            this.maxPacketLength = u16Checked(maxPacketLength);
            this.retransmissionTimeout = u16Checked(retransmissionTimeoutMillis);
            this.cumulativeAckTimeout = u16Checked(cumulativeAckTimeoutMillis);
            this.maxRetransmissions = u8(maxRetransmissions);
            this.maxCumulativeAcks = u8(maxCumulativeAcks);
            this.sessions = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(sessions)));
        }

        public byte[] encode() {
            byte[] p = new byte[10 + 3 * sessions.size()];
            p[0] = (byte) version;
            p[1] = (byte) maxOutstanding;
            p[2] = (byte) (maxPacketLength >> 8);
            p[3] = (byte) maxPacketLength;
            p[4] = (byte) (retransmissionTimeout >> 8);
            p[5] = (byte) retransmissionTimeout;
            p[6] = (byte) (cumulativeAckTimeout >> 8);
            p[7] = (byte) cumulativeAckTimeout;
            p[8] = (byte) maxRetransmissions;
            p[9] = (byte) maxCumulativeAcks;
            for (int i = 0; i < sessions.size(); i++) {
                SessionEntry s = sessions.get(i);
                p[10 + 3 * i] = (byte) s.id;
                p[11 + 3 * i] = (byte) s.type;
                p[12 + 3 * i] = (byte) s.version;
            }
            return p;
        }

        public static Parameters decode(byte[] p) throws ProtocolException {
            if (p.length < 10 || (p.length - 10) % 3 != 0) throw new ProtocolException("link parameters of " + p.length + " bytes");
            SessionEntry[] sessions = new SessionEntry[(p.length - 10) / 3];
            for (int i = 0; i < sessions.length; i++) {
                sessions[i] = new SessionEntry(p[10 + 3 * i] & 0xFF, p[11 + 3 * i] & 0xFF, p[12 + 3 * i] & 0xFF);
            }
            return new Parameters(p[0] & 0xFF, p[1] & 0xFF, u16(p, 2), u16(p, 4), u16(p, 6), p[8] & 0xFF, p[9] & 0xFF, sessions);
        }

        SessionEntry sessionOfType(int type) {
            for (SessionEntry s : sessions) if (s.type == type) return s;
            return null;
        }

        SessionEntry sessionWithId(int id) {
            for (SessionEntry s : sessions) if (s.id == id) return s;
            return null;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "version %d, %d outstanding, %d B packets, retransmit %d ms x%d, ack %d ms or %d packets, sessions %s",
                    version, maxOutstanding, maxPacketLength, retransmissionTimeout, maxRetransmissions, cumulativeAckTimeout, maxCumulativeAcks, sessions);
        }

        private static int u8(int v) {
            if (v < 0 || v > 0xFF) throw new IllegalArgumentException(v + " does not fit 8 bits");
            return v;
        }

        private static int u16Checked(int v) {
            if (v < 0 || v > 0xFFFF) throw new IllegalArgumentException(v + " does not fit 16 bits");
            return v;
        }
    }

    /** One row of the session table: ID, type (0 = control, 1 = file transfer, 2 = External Accessory) and version. */
    public static final class SessionEntry {
        public final int id, type, version;

        public SessionEntry(int id, int type, int version) {
            this.id = Parameters.u8(id);
            this.type = Parameters.u8(type);
            this.version = Parameters.u8(version);
        }

        @Override
        public String toString() {
            return id + ":" + type + "/" + version;
        }
    }
}
