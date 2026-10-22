package me.ri3d.openauto.carplay.iap2;

import static me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest.hex;
import static me.ri3d.openauto.carplay.iap2.CsmMessageTest.concat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.Down;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.Parameters;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.SessionEntry;
import me.ri3d.openauto.carplay.iap2.Iap2LinkLayer.State;

/**
 * The link layer against packets written and read by hand in this test: detection, every
 * synchronization field, framing, checksums, acknowledgements, retransmission, wraparound, faults,
 * start-up cases and the end of a link.
 */
public class Iap2LinkLayerTest {
    private static final int SYN = 0x80, ACK = 0x40, EAK = 0x20, RST = 0x10, SLP = 0x08;
    /** Version 1, 4 outstanding, 256-byte packets, retransmit after 1000 ms up to 3 times, acknowledge within 20 ms or 2 packets, control session 1 version 2. */
    private static final Parameters PROPOSAL = new Parameters(1, 4, 256, 1000, 20, 3, 2, new SessionEntry(1, 0, 2));
    private static final String PROPOSAL_HEX = "01" + "04" + "0100" + "03E8" + "0014" + "03" + "02" + "010002";
    /** The phone's answer: 2 outstanding, 64-byte packets, 800 ms x3, 10 ms or 2 packets, the proposed control session. */
    private static final String ANSWER_HEX = "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "010002";
    private static final byte[] DETECT = hex("FF550200EE10");

    private final List<byte[]> sent = new ArrayList<>();
    private final Recorder up = new Recorder();
    private Iap2LinkLayer link = new Iap2LinkLayer(sent::add, up, PROPOSAL, null);
    private long now = 10_000;
    private int phoneSeq;

    @Test
    public void synchronizesOnThePhonesAnswer() throws Exception {
        link.start(now, 100);
        assertArrayEquals("SYN as written by hand", pkt(SYN, 100, 0, 0, hex(PROPOSAL_HEX)), sent.get(0));
        receive(pkt(SYN | ACK, 40, 100, 0, hex(ANSWER_HEX)));
        assertEquals(State.UP, link.state());
        assertArrayEquals("ACK repeats the SYN's sequence number", pkt(ACK, 100, 40, 0), last());
        assertEquals(1, up.linkUps);
        Parameters n = link.negotiated();
        assertEquals(64, n.maxPacketLength);
        assertEquals(2, link.sendWindow());
        assertEquals(800, n.retransmissionTimeout);
        link.sendControl(new byte[]{1, 2, 3});
        assertArrayEquals("data on the control session, next sequence number", pkt(ACK, 101, 40, 1, new byte[]{1, 2, 3}), last());
    }

    /** Each refused field, and whether it is invalid or only unsupported here. */
    @Test
    public void refusesAnswersFieldByField() {
        String[][] cases = {
                // link version: packet meaning depends on it
                {"link version 2", "PARAMETERS_REFUSED", "02" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "010002"},
                // sessions: IDs, types and versions must be the proposed ones; exactly one control session
                {"no session", "PARAMETERS_REFUSED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02"},
                {"session ID 0", "PARAMETERS_REFUSED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "000002"},
                {"session ID repeated", "PARAMETERS_REFUSED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "010002" + "010002"},
                {"control session renamed", "UNSUPPORTED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "070002"},
                {"control session re-versioned", "UNSUPPORTED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "010001"},
                {"a session never proposed", "UNSUPPORTED", "01" + "02" + "0040" + "0320" + "000A" + "03" + "02" + "010002" + "080201"},
                // the phone's receive limits: only values that let nothing through
                {"no outstanding packet", "PARAMETERS_REFUSED", "01" + "00" + "0040" + "0320" + "000A" + "03" + "00" + "010002"},
                {"packets too short for a payload byte", "PARAMETERS_REFUSED", "01" + "02" + "000A" + "0320" + "000A" + "03" + "02" + "010002"},
                // timing and retries
                {"ZeroACK mode", "UNSUPPORTED", "01" + "02" + "0040" + "0000" + "0000" + "00" + "00" + "010002"},
                {"no retransmission timeout outside ZeroACK", "PARAMETERS_REFUSED", "01" + "02" + "0040" + "0000" + "0000" + "03" + "02" + "010002"},
                {"acknowledgement timeout not shorter", "PARAMETERS_REFUSED", "01" + "02" + "0040" + "0320" + "0320" + "03" + "02" + "010002"},
                {"payload of an impossible length", "PARAMETERS_REFUSED", "0102004003"},
        };
        for (String[] c : cases) {
            Recorder r = new Recorder();
            List<byte[]> out = new ArrayList<>();
            Iap2LinkLayer l = new Iap2LinkLayer(out::add, r, PROPOSAL, null);
            l.start(now, 100);
            byte[] answer = pkt(SYN | ACK, 40, 100, 0, hex(c[2]));
            l.receive(answer, 0, answer.length, now);
            assertEquals(c[0], Down.valueOf(c[1]), r.down);
            assertEquals(c[0] + ": RST sent", RST, out.get(out.size() - 1)[4] & 0xFF);
            assertEquals(c[0], 0, r.linkUps);
        }
        try {
            new Iap2LinkLayer(sent::add, up, new Parameters(1, 4, 256, 1000, 20, 3, 2), null);
            fail("a proposal without a control session was taken");
        } catch (IllegalArgumentException expected) {
            // nothing to carry the control session on
        }
    }

    /** The phone's limits are not ceilings under the proposal: they bound what this side sends. */
    @Test
    public void takesThePhonesLimitsEvenBeyondTheProposal() throws Exception {
        // 200 outstanding, 1024-byte packets, 800 ms, no retransmission, 9 cumulative acks
        sync(100, 40, "01" + "C8" + "0400" + "0320" + "000A" + "00" + "09" + "010002");
        assertEquals("send window capped at half the sequence space", 127, link.sendWindow());
        int count = sent.size();
        link.sendControl(new byte[600]);
        assertEquals("600 bytes fit one of the phone's 1024-byte packets", count + 1, sent.size());
        assertEquals(610, last().length);

        byte[] big = new byte[1000];
        receive(pkt(ACK, 41, 101, 1, big));
        assertEquals("a packet longer than the proposal but within the phone's own value is taken", 1000, up.bytes.size());
        for (int i = 0; i < 3; i++) receive(pkt(ACK, 42 + i, 101, 1, new byte[]{1}));
        assertArrayEquals("acknowledged at the smaller window (4 proposed), not at 9", pkt(ACK, 101, 44, 0), last());

        link.sendControl(new byte[]{2}); // stays unacknowledged
        link.poll(now + 800);
        assertEquals("zero retransmissions: the first timeout ends the link", Down.RETRANSMISSION_LIMIT, up.down);
    }

    @Test
    public void splitsMessagesIntoNegotiatedPacketsWithinTheWindow() throws Exception {
        sync(100, 40, ANSWER_HEX);
        byte[] message = new byte[500];
        new Random(5).nextBytes(message);
        int first = sent.size();
        link.sendControl(message);
        assertEquals("window of 2", first + 2, sent.size());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int expectedSeq = 101;
        while (joined.size() < message.length) {
            int before = sent.size();
            for (int i = first; i < before; i++) {
                Pkt p = parse(sent.get(i));
                assertTrue("packet of " + sent.get(i).length + " bytes", sent.get(i).length <= 64);
                assertEquals(1, p.session);
                assertEquals(expectedSeq++ & 0xFF, p.seq);
                joined.write(p.payload, 0, p.payload.length);
            }
            first = before;
            receive(pkt(ACK, 40, (expectedSeq - 1) & 0xFF, 0)); // acknowledges everything so far, opens the window
        }
        assertArrayEquals("the message arrives whole across " + (expectedSeq - 101) + " packets", message, joined.toByteArray());
        assertEquals(10, expectedSeq - 101);

        try {
            link.sendControl(new byte[CsmMessage.MAX_LENGTH + 1]);
            fail("longer than any control session message");
        } catch (IllegalArgumentException expected) {
            // the message limit, not the packet limit
        }
        link.sendControl(new byte[CsmMessage.MAX_LENGTH]);
        link.sendControl(new byte[CsmMessage.MAX_LENGTH]);
        try {
            link.sendControl(new byte[CsmMessage.MAX_LENGTH]);
            fail("the send queue grew without bound");
        } catch (IOException expected) {
            assertEquals("a full queue does not end the link", State.UP, link.state());
        }
    }

    @Test
    public void deliversInOrderOnceAndAcknowledgesCumulatively() throws Exception {
        sync(100, 40, ANSWER_HEX);
        int count = sent.size();
        receive(data("ab"));
        assertEquals("ab", up.text());
        link.poll(now + 9);
        assertEquals("acknowledgement may wait up to 10 ms", count, sent.size());
        link.poll(now + 10);
        assertArrayEquals(pkt(ACK, 100, 41, 0), last());
        receive(concat(data("c"), data("d")));
        assertArrayEquals("two packets acknowledged at once", pkt(ACK, 100, 43, 0), last());
        assertEquals("abcd", up.text());

        count = sent.size();
        receive(data("e"));
        link.sendControl(new byte[]{9});
        assertArrayEquals("the reply carries the acknowledgement", pkt(ACK, 101, 44, 1, new byte[]{9}), last());
        receive(pkt(ACK, 44, 101, 0));
        link.poll(now + 1000);
        assertEquals("no separate acknowledgement after it", count + 1, sent.size());
    }

    @Test
    public void duplicatesAndOutOfOrderPacketsAreDroppedAndAcknowledgedAgain() {
        sync(100, 40, ANSWER_HEX);
        byte[] first = data("a");
        receive(first);
        receive(first);
        assertEquals("a", up.text());
        assertArrayEquals("a repeat is answered at once", pkt(ACK, 100, 41, 0), last());
        assertEquals(1, link.duplicatePackets);
        byte[] second = data("b"), third = data("c");
        receive(third);
        assertEquals("a gap holds back what follows it", "a", up.text());
        assertArrayEquals(pkt(ACK, 100, 41, 0), last());
        assertEquals(1, link.outOfOrderPackets);
        receive(concat(second, third));
        assertEquals("abc", up.text());
    }

    /** Incoming EAK counts for its acknowledgement number; its list is not used, and this side never sends EAK. */
    @Test
    public void incomingEakAcknowledgesAndNoEakIsEverSent() throws Exception {
        sync(100, 40, ANSWER_HEX);
        link.sendControl(new byte[100]); // two packets, 101 and 102
        receive(pkt(ACK | EAK, 40, 101, 0, new byte[]{(byte) 103}));
        assertEquals(1, link.eakPackets);
        assertEquals("nothing delivered from an EAK", 0, up.bytes.size());
        int count = sent.size();
        link.poll(now + 800);
        assertEquals("only 102 is still unacknowledged", count + 1, sent.size());
        assertEquals(102, parse(last()).seq);
        receive(data("x"));
        receive(data("y"));
        receive(pkt(ACK, 44, 102, 1, new byte[]{1})); // out of order: dropped, acknowledged plainly
        for (byte[] p : sent) assertEquals("no EAK sent", 0, p[4] & EAK);
    }

    @Test
    public void corruptedPacketsAreDroppedAndTheStreamResynchronizes() {
        sync(100, 40, ANSWER_HEX);
        byte[] good = data("hello");
        int count = sent.size();
        byte[] badPayload = good.clone();
        badPayload[10] ^= 0x01;
        receive(badPayload);
        assertEquals("", up.text());
        assertEquals("a damaged packet is not acknowledged", count, sent.size());
        byte[] badHeader = good.clone();
        badHeader[5] ^= 0x01;
        receive(concat(badHeader, hex("00FF00FF5A01"), withLength(good, 10), withLength(good, 5), withLength(good, 257)));
        assertEquals("", up.text());
        receive(good);
        assertEquals("the intact copy gets through", "hello", up.text());
        assertEquals(6, link.corruptPackets);
        assertEquals(State.UP, link.state());
    }

    @Test
    public void fragmentedInputGivesTheSameResultAtEverySplit() {
        byte[] payload = new byte[40];
        new Random(7).nextBytes(payload);
        byte[] stream = concat(pkt(ACK, 40, 100, 0), pkt(ACK, 41, 100, 1, payload), pkt(ACK, 42, 100, 1, payload), pkt(ACK, 43, 100, 1, new byte[]{1}));
        byte[] expected = concat(payload, payload, new byte[]{1});
        for (int cut = 0; cut <= stream.length; cut++) {
            fresh(null);
            sync(100, 40, ANSWER_HEX);
            receive(Arrays.copyOfRange(stream, 0, cut));
            receive(Arrays.copyOfRange(stream, cut, stream.length));
            assertArrayEquals("cut at " + cut, expected, up.bytes.toByteArray());
        }
        fresh(null);
        sync(100, 40, ANSWER_HEX);
        for (byte b : stream) receive(new byte[]{b});
        assertArrayEquals("byte by byte", expected, up.bytes.toByteArray());
    }

    @Test
    public void unacknowledgedPacketsAreSentAgainThenTheLinkGivesUp() throws Exception {
        sync(100, 40, ANSWER_HEX);
        link.sendControl(new byte[100]); // two packets of 54 and 46 bytes
        int count = sent.size();
        link.poll(now + 799);
        assertEquals(count, sent.size());
        receive(data("x")); // phone traffic in between: the resent packets must carry the newer acknowledgement
        count = sent.size();
        link.poll(now + 800);
        assertEquals("both resent, oldest first", count + 2, sent.size());
        assertEquals(101, parse(sent.get(count)).seq);
        assertEquals(102, parse(sent.get(count + 1)).seq);
        assertEquals(41, parse(sent.get(count)).ack);
        receive(pkt(ACK, 41, 101, 0));
        count = sent.size();
        link.poll(now + 1600);
        assertEquals("only the unacknowledged one again", count + 1, sent.size());
        assertEquals(102, parse(last()).seq);
        link.poll(now + 2400);
        assertEquals(State.UP, link.state());
        link.poll(now + 3200);
        assertEquals("three retransmissions, then the link ends", Down.RETRANSMISSION_LIMIT, up.down);
        try {
            link.sendControl(new byte[]{1});
            fail("sent on a link that is down");
        } catch (IOException expected) {
            // link down
        }
    }

    @Test
    public void sequenceNumbersWrapInBothDirections() throws Exception {
        String wide = "01" + "04" + "0100" + "0320" + "000A" + "03" + "00" + "010002"; // acknowledge every packet at once
        sync(250, 252, wide);
        ByteArrayOutputStream expectedUp = new ByteArrayOutputStream();
        for (int i = 0; i < 600; i++) {
            byte[] b = {(byte) i, (byte) (i >> 8)};
            expectedUp.write(b, 0, 2);
            receive(pkt(ACK, ++phoneSeq & 0xFF, (250 + i) & 0xFF, 1, b));
            assertArrayEquals("acknowledged at once, number " + i, pkt(ACK, (250 + i) & 0xFF, phoneSeq & 0xFF, 0), last());
            link.sendControl(new byte[]{(byte) i});
            Pkt p = parse(last());
            assertEquals((251 + i) & 0xFF, p.seq);
            assertEquals((byte) i, p.payload[0]);
            int before = sent.size();
            receive(pkt(ACK, phoneSeq & 0xFF, (p.seq - 200) & 0xFF, 0)); // a stale number from before the wrap
            link.poll(now + 799);
            assertEquals("a stale acknowledgement acknowledges nothing", before, sent.size());
            receive(pkt(ACK, phoneSeq & 0xFF, p.seq, 0));
        }
        assertArrayEquals(expectedUp.toByteArray(), up.bytes.toByteArray());
        link.poll(now + 10_000);
        assertEquals("nothing left unacknowledged", State.UP, link.state());
    }

    @Test
    public void detectionComesFirstWhenTheProfileHasIt() {
        fresh(DETECT);
        link.start(now, 100);
        assertEquals(State.DETECTING, link.state());
        assertArrayEquals("the detection bytes, no SYN yet", DETECT, sent.get(0));
        link.poll(now + 1000);
        assertArrayEquals("repeated until answered", DETECT, last());
        receive(hex("00FF55"));
        receive(hex("0200EE"));
        assertEquals("half an echo is not enough", 2, sent.size());
        receive(hex("10"));
        assertEquals(State.SYNCHRONIZING, link.state());
        assertArrayEquals(pkt(SYN, 100, 0, 0, hex(PROPOSAL_HEX)), last());
        receive(concat(DETECT, pkt(SYN | ACK, 40, 100, 0, hex(ANSWER_HEX)))); // a late second echo is skipped
        assertEquals(State.UP, link.state());

        fresh(DETECT);
        link.start(now, 100);
        receive(hex("FF55040002" + "04EE08"));
        assertEquals("another answer is skipped, detection goes on", State.DETECTING, link.state());
        for (int i = 1; i <= 4; i++) link.poll(now + 1000L * i);
        assertEquals("no echo before the retries ran out: no iAP2", Down.DETECTION_FAILED, up.down);
        assertTrue(link.downDetail(), link.downDetail().contains("FF5504000204"));

        fresh(DETECT);
        link.start(now, 100);
        for (int i = 1; i <= 4; i++) link.poll(now + 1000L * i);
        assertEquals("never answered", Down.DETECTION_FAILED, up.down);
        assertEquals(4, sent.size());

        fresh(DETECT);
        link.start(now, 100);
        receive(pkt(SYN, 40, 0, 0, hex(ANSWER_HEX)));
        assertEquals("the phone starting synchronization itself", Down.UNSUPPORTED, up.down);
    }

    @Test
    public void synchronizationCasesOtherThanAccessoryFirstAreExplicit() {
        link.start(now, 100);
        for (int i = 1; i <= 3; i++) {
            link.poll(now + 1000L * i - 1);
            assertEquals(i, sent.size());
            link.poll(now + 1000L * i);
            assertArrayEquals("the same SYN again", sent.get(0), last());
        }
        link.poll(now + 4000);
        assertEquals(Down.SYNC_TIMEOUT, up.down);
        assertEquals(4, sent.size());

        fresh(null);
        link.start(now, 100);
        receive(pkt(SYN | ACK, 40, 99, 0, hex(ANSWER_HEX)));
        assertEquals("an answer to another SYN is ignored", State.SYNCHRONIZING, link.state());
        receive(pkt(SYN | ACK, 40, 100, 0, hex(ANSWER_HEX)));
        assertEquals(State.UP, link.state());
        int count = sent.size();
        receive(pkt(SYN | ACK, 40, 100, 0, hex(ANSWER_HEX)));
        assertEquals("our ACK was lost: the same SYN+ACK is acknowledged again", count + 1, sent.size());
        assertArrayEquals(pkt(ACK, 100, 40, 0), last());
        assertEquals(1, up.linkUps);
        receive(pkt(SYN | ACK, 41, 100, 0, hex(ANSWER_HEX)));
        assertEquals("a different SYN+ACK", Down.PROTOCOL_ERROR, up.down);

        fresh(null);
        link.start(now, 100);
        receive(pkt(SYN, 40, 0, 0, hex(ANSWER_HEX)));
        assertEquals("both sides sent SYN", Down.UNSUPPORTED, up.down);
        assertEquals(RST, parse(last()).control);

        fresh(null);
        sync(100, 40, ANSWER_HEX);
        receive(pkt(SYN, 50, 0, 0, hex(ANSWER_HEX)));
        assertEquals("the phone wants to start over", Down.RESYNC_REQUESTED, up.down);
        link.start(now, 7);
        assertEquals("this side starts a new link", 7, parse(last()).seq);
    }

    @Test
    public void resetSleepDisconnectAndCloseEndTheLinkAndClearIt() throws Exception {
        sync(100, 40, ANSWER_HEX);
        link.sendControl(new byte[300]); // window full, more waiting
        receive(pkt(RST, 41, 100, 0));
        assertEquals(Down.RESET_BY_PHONE, up.down);
        int count = sent.size();
        receive(data("late"));
        link.poll(now + 10_000);
        assertEquals("nothing delivered or sent after the end", "", up.text());
        assertEquals(count, sent.size());

        link.start(now, 30);
        assertEquals("a new SYN with the new number", 30, parse(last()).seq);
        count = sent.size();
        receive(pkt(ACK, 41, 105, 1, new byte[]{1, 2})); // next in line on the old link, its old session
        assertEquals("data from the old link is ignored", "", up.text());
        assertEquals("and not answered", count, sent.size());
        assertEquals(1, link.strayPackets);
        receive(pkt(SYN | ACK, 80, 30, 0, hex(ANSWER_HEX)));
        count = sent.size();
        link.sendControl(new byte[]{5});
        assertEquals("nothing of the old queue comes back", count + 1, sent.size());
        assertEquals(31, parse(last()).seq);

        fresh(null);
        sync(100, 40, ANSWER_HEX);
        receive(pkt(SLP, 41, 100, 0));
        assertEquals(Down.PHONE_SLEEP, up.down);
        fresh(null);
        sync(100, 40, ANSWER_HEX);
        link.transportClosed();
        assertEquals(Down.DISCONNECTED, up.down);
        fresh(null);
        sync(100, 40, ANSWER_HEX);
        link.close();
        assertEquals(RST, parse(last()).control);
        assertEquals(Down.CLOSED, up.down);

        Recorder r = new Recorder();
        Iap2LinkLayer broken = new Iap2LinkLayer(packet -> {
            throw new IOException("cable pulled");
        }, r, PROPOSAL, null);
        broken.start(now, 1);
        assertEquals(Down.TRANSPORT_ERROR, r.down);
    }

    @Test
    public void dataOutsideTheControlSessionIsAProtocolError() {
        for (int session : new int[]{9, 0}) {
            fresh(null);
            sync(100, 40, ANSWER_HEX);
            receive(pkt(ACK, 41, 100, session, new byte[]{1}));
            assertEquals("session " + session, Down.PROTOCOL_ERROR, up.down);
        }
    }

    // ---- helpers ----

    private void fresh(byte[] detect) {
        sent.clear();
        up.reset();
        link = new Iap2LinkLayer(sent::add, up, PROPOSAL, detect);
    }

    private void sync(int accessorySeq, int phoneInitialSeq, String answerHex) {
        link.start(now, accessorySeq);
        receive(pkt(SYN | ACK, phoneInitialSeq, accessorySeq, 0, hex(answerHex)));
        assertEquals(State.UP, link.state());
        phoneSeq = phoneInitialSeq;
    }

    /** The next phone data packet on the control session, acknowledging the accessory's SYN. */
    private byte[] data(String text) {
        return pkt(ACK, ++phoneSeq & 0xFF, 100, 1, text.getBytes());
    }

    private void receive(byte[] b) {
        link.receive(b, 0, b.length, now);
    }

    private byte[] last() {
        return sent.get(sent.size() - 1);
    }

    /** A phone packet written out here: 0xFF 0x5A, length, control, sequence, acknowledgement, session, checksums. */
    static byte[] pkt(int control, int seq, int ack, int session, byte... payload) {
        int len = payload.length == 0 ? 9 : 10 + payload.length;
        byte[] p = new byte[len];
        p[0] = (byte) 0xFF;
        p[1] = 0x5A;
        p[2] = (byte) (len >>> 8);
        p[3] = (byte) len;
        p[4] = (byte) control;
        p[5] = (byte) seq;
        p[6] = (byte) ack;
        p[7] = (byte) session;
        p[8] = checksum(p, 0, 8);
        if (payload.length > 0) {
            System.arraycopy(payload, 0, p, 9, payload.length);
            p[len - 1] = checksum(payload, 0, payload.length);
        }
        return p;
    }

    /** The same packet with another length field and a header checksum that matches it. */
    private static byte[] withLength(byte[] packet, int length) {
        byte[] p = packet.clone();
        p[2] = (byte) (length >>> 8);
        p[3] = (byte) length;
        p[8] = checksum(p, 0, 8);
        return p;
    }

    static byte checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i++) sum += b[i] & 0xFF;
        return (byte) (0x100 - (sum & 0xFF));
    }

    /** An accessory packet, with every field and checksum checked here. */
    static Pkt parse(byte[] p) {
        assertEquals(0xFF, p[0] & 0xFF);
        assertEquals(0x5A, p[1] & 0xFF);
        assertEquals("length field", p.length, (p[2] & 0xFF) << 8 | (p[3] & 0xFF));
        assertEquals("header checksum", checksum(p, 0, 8), p[8]);
        Pkt r = new Pkt();
        r.control = p[4] & 0xFF;
        r.seq = p[5] & 0xFF;
        r.ack = p[6] & 0xFF;
        r.session = p[7] & 0xFF;
        r.payload = p.length > 9 ? Arrays.copyOfRange(p, 9, p.length - 1) : new byte[0];
        if (p.length > 9) assertEquals("payload checksum", checksum(r.payload, 0, r.payload.length), p[p.length - 1]);
        return r;
    }

    static final class Pkt {
        int control, seq, ack, session;
        byte[] payload;
    }

    static final class Recorder implements Iap2LinkLayer.Upper {
        int linkUps;
        Down down;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void linkUp(Parameters negotiated, long now) {
            linkUps++;
        }

        @Override
        public void controlBytes(byte[] data, int off, int len, long now) {
            bytes.write(data, off, len);
        }

        @Override
        public void linkDown(Down reason, String detail) {
            down = reason;
        }

        String text() {
            return new String(bytes.toByteArray());
        }

        void reset() {
            linkUps = 0;
            down = null;
            bytes.reset();
        }
    }
}
