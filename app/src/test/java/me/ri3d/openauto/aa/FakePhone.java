package me.ri3d.openauto.aa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/**
 * A scripted phone for one TCP connection, written independently of the production framing code
 * (own frame parser/builder, JSSE TLS server). It plays the phone's half of protocol-reference §8.2.
 * With {@code video} set it streams those H.264 access units at ~30 fps after video START; without
 * it sends one random 100 KB "frame". It checks OUR side of the protocol only; it cannot prove what
 * Google's app does.
 */
final class FakePhone implements Runnable {
    final ServerSocket server;
    final LoopbackTlsServer tls;
    final List<byte[]> video;
    final boolean verbose;
    Socket sock;
    DataInputStream in;
    OutputStream out;
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch mediaAcked = new CountDownLatch(1), audioAcked = new CountDownLatch(1),
            micData = new CountDownLatch(1), bound = new CountDownLatch(1), sensors = new CountDownLatch(2),
            pinged = new CountDownLatch(1), shutdownAcked = new CountDownLatch(1), focusResp = new CountDownLatch(1),
            finished = new CountDownLatch(1);
    volatile Exception error;
    volatile long videoAcks, touches, videoSent, videoHeldBack;
    private volatile int maxUnacked = Integer.MAX_VALUE, streamGeneration;
    private boolean tlsStarted;
    private volatile boolean streaming;
    private Thread streamer;
    private final Random rnd = new Random(42);

    FakePhone(TlsCredentials creds, ServerSocket server, List<byte[]> video, boolean verbose) throws Exception {
        this.server = server;
        this.tls = new LoopbackTlsServer();
        this.video = video;
        this.verbose = verbose;
    }

    public void run() {
        try {
            sock = server.accept();
            sock.setTcpNoDelay(true);
            in = new DataInputStream(sock.getInputStream());
            out = sock.getOutputStream();
            say("head unit connected from " + sock.getRemoteSocketAddress());
            while (readMessage()) { /* loop */ }
        } catch (Exception e) {
            if (!(e instanceof java.io.EOFException || e instanceof java.net.SocketException)) error = e;
        } finally {
            streaming = false;
            say("connection ended" + (error == null ? "" : ": " + error));
            finished.countDown();
        }
    }

    private void say(String s) {
        events.add(s);
        if (verbose) System.out.println("[fake-phone] " + s);
    }

    /** Own frame parser (protocol-reference §1), independent of FrameAssembler. */
    private boolean readMessage() throws Exception {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int channel = -1;
        boolean control = false;
        while (true) {
            byte[] h = new byte[4];
            try { in.readFully(h); } catch (java.io.EOFException e) { return false; }
            int ch = h[0] & 0xFF, flags = h[1] & 0xFF, len = ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
            if ((flags & 3) == Wire.FRAME_FIRST) in.readFully(new byte[4]);
            byte[] payload = new byte[len];
            in.readFully(payload);
            if ((flags & Wire.FLAG_ENCRYPTED) != 0) payload = tls.decrypt(payload);
            if (channel >= 0 && channel != ch) throw new IOException("interleaved channels from head unit");
            channel = ch;
            control = (flags & Wire.FLAG_CONTROL) != 0;
            msg.write(payload);
            int type = flags & 3;
            if (type == Wire.FRAME_BULK || type == Wire.FRAME_LAST) break;
        }
        byte[] m = msg.toByteArray();
        int id = ((m[0] & 0xFF) << 8) | (m[1] & 0xFF);
        handle(channel, control, id, m, 2, m.length - 2);
        return true;
    }

    /** Synchronized: encrypt + write must be atomic per message, the TLS server is shared by threads. */
    synchronized void send(int channel, boolean control, int id, byte[] body, boolean encrypted) throws Exception {
        byte[] full = new byte[body.length + 2];
        full[0] = (byte) (id >> 8);
        full[1] = (byte) id;
        System.arraycopy(body, 0, full, 2, body.length);
        int chunks = Math.max(1, (full.length + 16383) / 16384);
        for (int i = 0; i < chunks; i++) {
            int off = i * 16384, len = Math.min(16384, full.length - off);
            byte[] plain = java.util.Arrays.copyOfRange(full, off, off + len);
            byte[] payload = encrypted ? tls.encrypt(plain) : plain;
            int type = chunks == 1 ? Wire.FRAME_BULK : i == 0 ? Wire.FRAME_FIRST : i == chunks - 1 ? Wire.FRAME_LAST : Wire.FRAME_MIDDLE;
            int flags = type | (control ? Wire.FLAG_CONTROL : 0) | (encrypted ? Wire.FLAG_ENCRYPTED : 0);
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(channel); f.write(flags); f.write(payload.length >> 8); f.write(payload.length);
            if (type == Wire.FRAME_FIRST) { f.write(full.length >>> 24); f.write(full.length >>> 16); f.write(full.length >>> 8); f.write(full.length); }
            f.write(payload);
            out.write(f.toByteArray());
            out.flush();
        }
    }

    static byte[] proto(ProtoWriter w) {
        return w.toBytes();
    }

    private void handle(int ch, boolean control, int id, byte[] d, int off, int len) throws Exception {
        if (!(ch == Wire.CH_VIDEO && id == Wire.AV_MEDIA_ACK) && !(ch == Wire.CH_INPUT && id == Wire.INPUT_EVENT)) {
            events.add(ch + ":" + Integer.toHexString(id));
        }
        if (ch == Wire.CH_CONTROL) {
            switch (id) {
                case Wire.VERSION_REQUEST:
                    assertEquals(4, len);
                    say("version request " + (d[off] << 8 | d[off + 1]) + "." + (d[off + 2] << 8 | d[off + 3]));
                    send(0, false, Wire.VERSION_RESPONSE, new byte[]{0, 1, 0, 1, 0, 0}, false);
                    break;
                case Wire.SSL_HANDSHAKE: {
                    if (!tlsStarted) { tls.startHandshake(); tlsStarted = true; }
                    byte[] reply = tls.exchange(java.util.Arrays.copyOfRange(d, off, off + len));
                    say("tls handshake flight in " + len + " B, out " + reply.length + " B");
                    if (reply.length > 0) send(0, false, Wire.SSL_HANDSHAKE, reply, false);
                    break;
                }
                case Wire.AUTH_COMPLETE:
                    assertEquals(0, new ProtoReader(d, off, len).nextField(1).int32());
                    say("auth complete; cipher " + tls.ssl.getSession().getProtocol() + " " + tls.ssl.getSession().getCipherSuite());
                    send(0, false, Wire.SERVICE_DISCOVERY_REQUEST, proto(new ProtoWriter().string(4, "Fake Phone").string(5, "Test")), true);
                    break;
                case Wire.SERVICE_DISCOVERY_RESPONSE: {
                    List<Integer> channels = new ArrayList<>();
                    ProtoReader r = new ProtoReader(d, off, len);
                    String name = null;
                    while (r.next()) {
                        if (r.field() == 1) {
                            ProtoReader cd = r.message(new ProtoReader());
                            while (cd.next()) { if (cd.field() == 1) channels.add(cd.int32()); else cd.skip(); }
                        } else if (r.field() == 2) name = r.string(); else r.skip();
                    }
                    assertEquals("OpenAuto", name);
                    say("discovery: channels " + channels);
                    events.add("channels=" + channels);
                    for (int c : channels) send(c, true, Wire.CHANNEL_OPEN_REQUEST, proto(new ProtoWriter().int32(1, 0).int32(2, c)), true);
                    break;
                }
                case Wire.PING_REQUEST:
                    pinged.countDown();
                    send(0, false, Wire.PING_RESPONSE, proto(new ProtoWriter().uint(1, System.currentTimeMillis())), true);
                    break;
                case Wire.AUDIO_FOCUS_RESPONSE:
                    assertEquals(Wire.AUDIO_FOCUS_STATE_GAIN, new ProtoReader(d, off, len).nextField(1).int32());
                    focusResp.countDown();
                    break;
                case Wire.SHUTDOWN_REQUEST:
                    say("head unit requested shutdown");
                    send(0, false, Wire.SHUTDOWN_RESPONSE, new byte[0], true);
                    break;
                case Wire.SHUTDOWN_RESPONSE:
                    shutdownAcked.countDown();
                    break;
                default:
                    say("unexpected control " + Integer.toHexString(id));
            }
            return;
        }
        if (id == Wire.CHANNEL_OPEN_REQUEST) return;
        if (id == Wire.CHANNEL_OPEN_RESPONSE) {
            assertTrue("open response must carry the CONTROL flag", control);
            assertEquals(0, new ProtoReader(d, off, len).nextField(1).int32());
            say("channel " + Wire.channelName(ch) + " open");
            if (ch == Wire.CH_SENSOR) {
                send(ch, false, Wire.SENSOR_START_REQUEST, proto(new ProtoWriter().uint(1, Wire.SENSOR_DRIVING_STATUS).uint(2, 0)), true);
                send(ch, false, Wire.SENSOR_START_REQUEST, proto(new ProtoWriter().uint(1, Wire.SENSOR_NIGHT_DATA).uint(2, 0)), true);
            } else if (ch == Wire.CH_INPUT) {
                send(ch, false, Wire.BINDING_REQUEST, proto(new ProtoWriter().int32(1, Wire.BTN_TOGGLE_PLAY)), true);
            } else if (ch == Wire.CH_NAVIGATION) { // route started, turn left into Hauptstraße in 350 m
                send(ch, false, Wire.NAV_STATUS, proto(new ProtoWriter().uint(1, Wire.NAV_ACTIVE)), true);
                byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
                send(ch, false, Wire.NAV_TURN, proto(new ProtoWriter().string(1, "Hauptstraße").uint(2, 1).uint(3, 4)
                        .bytes(4, png, 0, png.length).uint(5, 0).uint(6, 0)), true);
                send(ch, false, Wire.NAV_DISTANCE, proto(new ProtoWriter().uint(1, 352).uint(2, 25).uint(3, 350_000).uint(4, 1)), true);
            } else {
                send(ch, false, Wire.AV_SETUP_REQUEST, proto(new ProtoWriter().uint(1, 0)), true);
            }
            return;
        }
        switch (ch) {
            case Wire.CH_SENSOR:
                if (id == Wire.SENSOR_EVENT) {
                    ProtoReader r = new ProtoReader(d, off, len);
                    r.next();
                    say("sensor event field " + r.field());
                    if (r.field() == 13 || r.field() == 10) sensors.countDown();
                }
                break;
            case Wire.CH_VIDEO:
                if (id == Wire.AV_SETUP_RESPONSE) {
                    ProtoReader setup = new ProtoReader(d, off, len);
                    assertEquals(Wire.AV_SETUP_OK, setup.nextField(1).int32());
                    maxUnacked = setup.nextField(2).int32();
                    say("video setup OK, max_unacked " + maxUnacked);
                    send(ch, false, Wire.AV_START, proto(new ProtoWriter().int32(1, 7).uint(2, 0)), true);
                    if (video == null) {
                        byte[] frame = new byte[8 + 100_000];
                        rnd.nextBytes(frame);
                        send(ch, false, Wire.AV_MEDIA_WITH_TIMESTAMP, frame, true);
                    } else {
                        startStreaming();
                    }
                } else if (id == Wire.AV_MEDIA_ACK) {
                    ProtoReader r = new ProtoReader(d, off, len);
                    assertEquals(7, r.nextField(1).int32());
                    assertEquals(1, r.nextField(2).int32());
                    videoAcks++;
                    mediaAcked.countDown();
                } else if (id == Wire.VIDEO_FOCUS_INDICATION) {
                    int mode = new ProtoReader(d, off, len).nextField(1).int32();
                    say("video focus " + (mode == Wire.VIDEO_FOCUS_FOCUSED ? "FOCUSED" : "UNFOCUSED"));
                    if (mode == Wire.VIDEO_FOCUS_FOCUSED) events.add("video-focused");
                    if (video != null) {
                        if (mode == Wire.VIDEO_FOCUS_FOCUSED && !streaming && streamer != null) startStreaming();
                        if (mode == Wire.VIDEO_FOCUS_UNFOCUSED) streaming = false;
                    }
                }
                break;
            case Wire.CH_MEDIA_AUDIO:
                if (id == Wire.AV_SETUP_RESPONSE) {
                    send(ch, false, Wire.AV_START, proto(new ProtoWriter().int32(1, 3).uint(2, 0)), true);
                    send(ch, false, Wire.AV_MEDIA_WITH_TIMESTAMP, new byte[8 + 3840], true);
                } else if (id == Wire.AV_MEDIA_ACK) {
                    assertEquals(3, new ProtoReader(d, off, len).nextField(1).int32());
                    audioAcked.countDown();
                }
                break;
            case Wire.CH_AV_INPUT:
                if (id == Wire.AV_SETUP_RESPONSE) {
                    send(ch, false, Wire.AV_INPUT_OPEN_REQUEST, proto(new ProtoWriter().bool(1, true).bool(2, false).bool(3, false).int32(4, 1)), true);
                } else if (id == Wire.AV_INPUT_OPEN_RESPONSE) {
                    ProtoReader r = new ProtoReader(d, off, len);
                    r.nextField(1).int32();
                    int value = r.nextField(2).int32();
                    say("mic open response value " + value + (value == 0 ? " (OK)" : " (FAIL)"));
                    if (!verbose) assertEquals("mic open must succeed", 0, value);
                } else if (id == Wire.AV_MEDIA_WITH_TIMESTAMP) {
                    long ts = 0;
                    for (int i = 0; i < 8; i++) ts = (ts << 8) | (d[off + i] & 0xFF);
                    if (micData.getCount() > 0) say("mic data " + (len - 8) + " B, ts " + ts);
                    send(ch, false, Wire.AV_MEDIA_ACK, proto(new ProtoWriter().int32(1, 0).uint(2, 1)), true);
                    micData.countDown();
                }
                break;
            case Wire.CH_INPUT:
                if (id == Wire.BINDING_RESPONSE) {
                    assertEquals(0, new ProtoReader(d, off, len).nextField(1).int32());
                    bound.countDown();
                } else if (id == Wire.INPUT_EVENT) {
                    touches++;
                    events.add("touch");
                    if (verbose) {
                        ProtoReader r = new ProtoReader(d, off, len);
                        StringBuilder sb = new StringBuilder("input event");
                        while (r.next()) {
                            if (r.field() == 3) {
                                ProtoReader t = r.message(new ProtoReader());
                                while (t.next()) {
                                    if (t.field() == 1) {
                                        ProtoReader l = t.message(new ProtoReader());
                                        while (l.next()) { sb.append(' ').append(l.field() == 1 ? "x=" : l.field() == 2 ? "y=" : "id=").append(l.int32()); }
                                    } else if (t.field() == 3) sb.append(" action=").append(t.int32()); else t.skip();
                                }
                            } else if (r.field() == 4) {
                                sb.append(" button");
                                r.skip();
                            } else r.skip();
                        }
                        say(sb.toString());
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * Streams the file from its first access unit (a keyframe), like a phone that gained video focus.
     * Honours max_unacked the way the protocol intends: no new frame while that many are unacknowledged.
     */
    private void startStreaming() {
        streaming = true;
        final int generation = ++streamGeneration;
        streamer = new Thread(() -> {
            long pts = 0;
            int i = 0;
            try {
                while (streaming && generation == streamGeneration) {
                    if (videoSent - videoAcks >= maxUnacked) {
                        videoHeldBack++;
                        Thread.sleep(5);
                        continue;
                    }
                    byte[] au = video.get(i);
                    byte[] body = new byte[8 + au.length];
                    long ts = pts;
                    for (int k = 0; k < 8; k++) body[k] = (byte) (ts >>> (56 - 8 * k));
                    System.arraycopy(au, 0, body, 8, au.length);
                    videoSent++;
                    send(Wire.CH_VIDEO, false, Wire.AV_MEDIA_WITH_TIMESTAMP, body, true);
                    pts += 33333;
                    i = (i + 1) % video.size();
                    Thread.sleep(33);
                }
            } catch (Exception e) {
                if (streaming) error = e;
            }
        }, "fake-phone-video");
        streamer.setDaemon(true);
        streamer.start();
    }

    void close() {
        streaming = false;
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        tls.close();
    }
}
