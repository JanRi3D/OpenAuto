package me.ri3d.openauto.aa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;
import me.ri3d.openauto.transport.Transport;

/**
 * One protocol session with one phone over one transport. Single use: a new attempt is a new
 * Session, so callbacks from an old attempt can be told apart by identity.
 *
 * Startup (protocol-reference §8.2): VERSION_REQUEST -> VERSION_RESPONSE -> SSL_HANDSHAKE flights ->
 * AUTH_COMPLETE -> SERVICE_DISCOVERY_REQUEST/RESPONSE -> channel opens -> sensors/video/audio/input.
 * {@link State#PROJECTING} is entered only when the phone sends the video START indication.
 */
public final class Session implements FrameAssembler.Sink {
    public enum State { CONNECTING, VERSION, HANDSHAKE, DISCOVERY, READY, PROJECTING, CLOSED }

    public interface Listener {
        void onState(Session s, State state, String detail);
        void onLog(Session s, String line);
        /** The phone asked for projected (true) or native (false) video focus, e.g. via Android Auto's Exit entry. */
        default void onVideoFocusRequest(Session s, boolean projected) { }
    }

    /** Re-evaluated periodically for the Auto night mode. */
    public interface NightSource {
        boolean isNight();
    }

    public static final class Config {
        public int videoWidth = 800, videoHeight = 480, resEnum = Wire.RES_480P, fpsEnum = Wire.FPS_30, dpi = 140;
        /**
         * Pixels of the video frame the phone must leave unused (split evenly on both sides), so that the
         * remaining area has the display's aspect ratio; the head unit crops them away. 0 = use the full frame.
         */
        public int marginWidth, marginHeight;
        /** Run on the reader thread just before the discovery response is built: last chance to adjust video fields. */
        public Runnable beforeDiscovery;
        /** Run on the reader thread before the TLS engine is chosen (the Android side tries the system library here). */
        public Runnable prepareTls;
        public boolean media = true, speech = true, mic = true;
        public boolean night;
        public NightSource nightSource;
        public String headUnitName = "OpenAuto", carModel = "Universal", carYear = "2026", carSerial = "OPENAUTO0001",
                manufacturer = "OpenAuto", model = "OpenAuto receiver", swBuild = "1", swVersion = "0.1";
        public boolean leftHandDrive = true;
        public int pingIntervalMs = 5000;
        /** Longest time the phone may take in each startup phase (version, TLS handshake, discovery). */
        public int handshakeTimeoutMs = 10000;
        /** Log every non-media message in both directions with a short hex dump (diagnostics). */
        public boolean trace;

        public void setResolution(int res) {
            if (res >= 1080) { videoWidth = 1920; videoHeight = 1080; resEnum = Wire.RES_1080P; }
            else if (res >= 720) { videoWidth = 1280; videoHeight = 720; resEnum = Wire.RES_720P; }
            else { videoWidth = 800; videoHeight = 480; resEnum = Wire.RES_480P; }
        }
    }

    final Config cfg;
    private final Transport transport;
    private final TlsCredentials creds;
    private final Listener listener;

    private Tls tls;
    private FrameWriter writer;
    private FrameAssembler assembler;
    private final Object sendLock = new Object();
    private final ProtoWriter w = new ProtoWriter(256);
    private final ProtoReader r = new ProtoReader();

    private volatile State state = State.CONNECTING;
    private volatile boolean closed;
    private volatile String closeReason;
    private Thread reader, pinger;
    public volatile int pingsSent, pingsAnswered;
    private volatile long lastRx = System.currentTimeMillis();
    private final ProtoWriter pingW = new ProtoWriter(16); // the pinger thread must not share the reader's writer

    private final Channel[] channels = new Channel[9];
    private final List<Channel> declared = new ArrayList<>();
    public final VideoChannel video;
    public final AudioChannel mediaAudio, speechAudio, systemAudio;
    public final MicChannel mic;
    public final SensorChannel sensor;
    public final InputChannel input;

    public volatile String phoneName, phoneBrand;
    public volatile int phoneVersionMajor, phoneVersionMinor;

    public Session(Transport transport, TlsCredentials creds, Config cfg, Media media, Listener listener) {
        this.transport = transport;
        this.creds = creds;
        this.cfg = cfg;
        this.listener = listener;
        // Declaration order follows openauto (§8.1): mic, media, speech, system, sensor, video, input.
        mic = cfg.mic ? declare(new MicChannel(this, media.mic())) : null;
        mediaAudio = cfg.media ? declare(new AudioChannel(this, Wire.CH_MEDIA_AUDIO, Wire.AUDIO_TYPE_MEDIA, 48000, 2, media.audio(Wire.CH_MEDIA_AUDIO))) : null;
        speechAudio = cfg.speech ? declare(new AudioChannel(this, Wire.CH_SPEECH_AUDIO, Wire.AUDIO_TYPE_SPEECH, 16000, 1, media.audio(Wire.CH_SPEECH_AUDIO))) : null;
        systemAudio = declare(new AudioChannel(this, Wire.CH_SYSTEM_AUDIO, Wire.AUDIO_TYPE_SYSTEM, 16000, 1, media.audio(Wire.CH_SYSTEM_AUDIO)));
        sensor = declare(new SensorChannel(this));
        video = declare(new VideoChannel(this, media.video()));
        input = declare(new InputChannel(this));
    }

    private <T extends Channel> T declare(T c) {
        channels[c.id] = c;
        declared.add(c);
        return c;
    }

    /** What to call the phone in the UI: a real phone sent brand "samsung SM-S948B" and name "Android". */
    public String phoneLabel() {
        String b = phoneBrand, n = phoneName;
        if (b != null && !b.isEmpty()) return b;
        return n == null ? "" : n;
    }

    public State state() { return state; }
    public boolean isClosed() { return closed; }
    public String closeReason() { return closeReason; }
    public String transportName() { return transport.describe(); }
    public String tlsInfo() { return tls == null ? null : tls.cipherSuite(); }
    public Config config() { return cfg; }

    public void start() {
        reader = new Thread(this::run, "aa-reader");
        reader.start();
    }

    private void run() {
        try {
            if (cfg.prepareTls != null) cfg.prepareTls.run();
            Tls fast = NativeTls.open(creds); // null unless the device's OpenSSL passed its self-test
            tls = fast != null ? fast : new TlsEngine(creds);
            writer = new FrameWriter(transport);
            assembler = new FrameAssembler(this, tls);
            setState(State.VERSION, null);
            byte[] v = {Wire.VERSION_MAJOR >> 8, Wire.VERSION_MAJOR, Wire.VERSION_MINOR >> 8, Wire.VERSION_MINOR};
            sendPlain(Wire.CH_CONTROL, Wire.VERSION_REQUEST, v, v.length);
            startWatchdog();
            byte[] buf = new byte[16384];
            while (!closed) {
                int n = transport.read(buf, buf.length);
                if (n < 0) {
                    close("connection closed by the phone");
                    return;
                }
                lastRx = System.currentTimeMillis();
                assembler.feed(buf, 0, n);
            }
        } catch (IOException e) {
            close(closed ? closeReason : e.getMessage());
        } catch (RuntimeException e) {
            close("internal error: " + e);
        }
    }

    // ---- dispatch --------------------------------------------------------------------------------

    @Override
    public void onMessage(int channel, boolean control, byte[] data, int len) throws IOException {
        int id = FrameAssembler.messageId(data);
        if (cfg.trace && (channel == Wire.CH_CONTROL || (id != Wire.AV_MEDIA_WITH_TIMESTAMP && id != Wire.AV_MEDIA))) {
            log("rx " + Wire.channelName(channel) + (control ? " [ctl]" : "") + " 0x" + Integer.toHexString(id) + " len " + (len - 2) + hex(data, 2, len - 2));
        }
        if (channel == Wire.CH_CONTROL) {
            onControl(id, data, 2, len - 2);
            return;
        }
        Channel c = channel < channels.length ? channels[channel] : null;
        if (c == null) {
            log("message 0x" + Integer.toHexString(id) + " on undeclared channel " + channel);
            return;
        }
        c.onMessage(id, data, 2, len - 2);
    }

    private void onControl(int id, byte[] d, int off, int len) throws IOException {
        switch (id) {
            case Wire.VERSION_RESPONSE: {
                if (len < 6) throw new IOException("short version response");
                phoneVersionMajor = ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
                phoneVersionMinor = ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
                int status = ((d[off + 4] & 0xFF) << 8) | (d[off + 5] & 0xFF);
                log("version: phone " + phoneVersionMajor + "." + phoneVersionMinor + " status " + status);
                if (status != Wire.VERSION_STATUS_MATCH) {
                    close("protocol version mismatch (phone " + phoneVersionMajor + "." + phoneVersionMinor + ")");
                    return;
                }
                setState(State.HANDSHAKE, null);
                byte[] flight = tls.beginHandshake();
                sendPlain(Wire.CH_CONTROL, Wire.SSL_HANDSHAKE, flight, flight.length);
                break;
            }
            case Wire.SSL_HANDSHAKE: {
                byte[] flight;
                try {
                    flight = tls.continueHandshake(d, off, len);
                } catch (IOException e) {
                    // The next attempt uses the Java engine: this phone and the system library do not get along.
                    if (tls instanceof NativeTls) NativeTls.disable(e.getMessage());
                    throw e;
                }
                if (flight.length > 0) sendPlain(Wire.CH_CONTROL, Wire.SSL_HANDSHAKE, flight, flight.length);
                if (tls.isHandshakeDone()) {
                    log("tls: " + tls.cipherSuite());
                    w.reset().uint(1, Wire.STATUS_OK);
                    sendPlain(Wire.CH_CONTROL, Wire.AUTH_COMPLETE, w.buffer(), w.size());
                    setState(State.DISCOVERY, null);
                }
                break;
            }
            case Wire.SERVICE_DISCOVERY_REQUEST: {
                r.reset(d, off, len);
                while (r.next()) {
                    if (r.field() == 4) phoneName = r.string();
                    else if (r.field() == 5) phoneBrand = r.string();
                    else r.skip();
                }
                log("discovery request from " + phoneBrand + " / " + phoneName);
                if (cfg.beforeDiscovery != null) cfg.beforeDiscovery.run();
                log("video " + cfg.videoWidth + "x" + cfg.videoHeight + ", margins " + cfg.marginWidth + "x" + cfg.marginHeight + ", dpi " + cfg.dpi);
                ProtoWriter resp = Messages.discovery(this, declared);
                send(Wire.CH_CONTROL, false, Wire.SERVICE_DISCOVERY_RESPONSE, resp);
                setState(State.READY, phoneLabel());
                startPinger();
                break;
            }
            case Wire.AUDIO_FOCUS_REQUEST: {
                int type = 0;
                r.reset(d, off, len);
                while (r.next()) {
                    if (r.field() == 1) type = r.int32(); else r.skip();
                }
                boolean release = type == Wire.AUDIO_FOCUS_RELEASE;
                if (mediaAudio != null) mediaAudio.setDucked(type == Wire.AUDIO_FOCUS_GAIN_TRANSIENT || type == Wire.AUDIO_FOCUS_GAIN_NAVI);
                w.reset().uint(1, release ? Wire.AUDIO_FOCUS_STATE_LOSS : Wire.AUDIO_FOCUS_STATE_GAIN);
                send(Wire.CH_CONTROL, false, Wire.AUDIO_FOCUS_RESPONSE, w);
                break;
            }
            case Wire.NAVIGATION_FOCUS_REQUEST:
                w.reset().uint(1, 2);
                send(Wire.CH_CONTROL, false, Wire.NAVIGATION_FOCUS_RESPONSE, w);
                break;
            case Wire.SHUTDOWN_REQUEST:
                w.reset();
                send(Wire.CH_CONTROL, false, Wire.SHUTDOWN_RESPONSE, w);
                close("the phone ended the session");
                break;
            case Wire.SHUTDOWN_RESPONSE:
                close(closeReason != null ? closeReason : "stopped");
                break;
            case Wire.PING_RESPONSE:
                pingsAnswered++;
                break;
            case Wire.PING_REQUEST: {
                // Some phones ping the head unit; echo the timestamp back.
                long ts = 0;
                r.reset(d, off, len);
                while (r.next()) {
                    if (r.field() == 1) ts = r.int64(); else r.skip();
                }
                w.reset().uint(1, ts);
                send(Wire.CH_CONTROL, false, Wire.PING_RESPONSE, w);
                break;
            }
            case Wire.VOICE_SESSION_REQUEST:
                log("voice session request");
                break;
            default:
                log("control: unhandled message 0x" + Integer.toHexString(id));
        }
    }

    // ---- sending -------------------------------------------------------------------------------

    void send(int channel, boolean control, int msgId, ProtoWriter body) throws IOException {
        send(channel, control, msgId, body.buffer(), body.size());
    }

    /** Encrypted send; only valid after the TLS handshake. */
    void send(int channel, boolean control, int msgId, byte[] body, int len) throws IOException {
        synchronized (sendLock) {
            if (closed) throw new IOException("session closed");
            if (tls == null || !tls.isHandshakeDone()) throw new IOException("encrypted send before handshake");
            if (cfg.trace && msgId != Wire.AV_MEDIA_ACK && msgId != Wire.AV_MEDIA_WITH_TIMESTAMP) {
                log("tx " + Wire.channelName(channel) + (control ? " [ctl]" : "") + " 0x" + Integer.toHexString(msgId) + " len " + len + hex(body, 0, len));
            }
            writer.send(channel, control, msgId, body, 0, len, tls);
        }
    }

    private void sendPlain(int channel, int msgId, byte[] body, int len) throws IOException {
        synchronized (sendLock) {
            if (closed) throw new IOException("session closed");
            if (cfg.trace && msgId != Wire.PING_REQUEST) {
                log("tx plain " + Wire.channelName(channel) + " 0x" + Integer.toHexString(msgId) + " len " + len + (msgId == Wire.SSL_HANDSHAKE ? "" : hex(body, 0, len)));
            }
            writer.send(channel, false, msgId, body, 0, len, null);
        }
    }

    private static String hex(byte[] d, int off, int len) {
        if (len <= 0) return "";
        StringBuilder sb = new StringBuilder(" :");
        int n = Math.min(len, 96);
        for (int i = 0; i < n; i++) sb.append(' ').append(Character.forDigit((d[off + i] >> 4) & 0xF, 16)).append(Character.forDigit(d[off + i] & 0xF, 16));
        if (len > n) sb.append(" …");
        return sb.toString();
    }

    // ---- ping / night mode -----------------------------------------------------------------------

    private void startPinger() {
        pinger = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(cfg.pingIntervalMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed) return;
                // Liveness is "any data from the phone", not "ping answered": a real phone was observed
                // streaming video and audio while never answering aasdk-style pings.
                long idle = System.currentTimeMillis() - lastRx;
                if (idle > 3L * cfg.pingIntervalMs) {
                    close("the phone stopped responding (no data for " + idle / 1000 + " s)");
                    return;
                }
                try {
                    pingsSent++;
                    pingW.reset().uint(1, System.nanoTime() / 1000);
                    sendPlain(Wire.CH_CONTROL, Wire.PING_REQUEST, pingW.buffer(), pingW.size());
                    if (cfg.nightSource != null) sensor.setNight(cfg.nightSource.isNight());
                } catch (IOException e) {
                    close("ping failed: " + e.getMessage());
                    return;
                }
            }
        }, "aa-pinger");
        pinger.setDaemon(true);
        pinger.start();
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Polite stop: asks the phone to shut down, then closes after a short grace period. */
    public void requestShutdown(String reason) {
        if (closed) return;
        closeReason = reason;
        if (tls != null && tls.isHandshakeDone()) {
            try {
                w.reset().uint(1, 1); // ShutdownReason QUIT
                send(Wire.CH_CONTROL, false, Wire.SHUTDOWN_REQUEST, w);
            } catch (IOException ignored) {
            }
            Thread t = new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                close(reason);
            }, "aa-shutdown");
            t.setDaemon(true);
            t.start();
        } else {
            close(reason);
        }
    }

    /** Hard stop; idempotent; safe from any thread. */
    public void close(String reason) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeReason = reason;
        }
        for (Channel c : channels) {
            if (c != null) {
                try { c.close(); } catch (RuntimeException ignored) { }
            }
        }
        try { transport.close(); } catch (IOException | RuntimeException ignored) { }
        if (tls != null) tls.close();
        if (pinger != null) pinger.interrupt();
        setState(State.CLOSED, reason);
    }

    void setState(State st, String detail) {
        if (state == State.CLOSED && st != State.CLOSED) return;
        state = st;
        stateSince = System.currentTimeMillis();
        listener.onState(this, st, detail);
    }

    private volatile long stateSince = System.currentTimeMillis();

    /**
     * A phone that accepts the connection but never answers would otherwise leave the session waiting
     * forever (observed with a real phone whose head-unit server was left over from an aborted attempt).
     */
    private void startWatchdog() {
        Thread t = new Thread(() -> {
            while (!closed) {
                State st = state;
                if (st == State.READY || st == State.PROJECTING || st == State.CLOSED) return;
                if (System.currentTimeMillis() - stateSince > cfg.handshakeTimeoutMs) {
                    String waited = " (waited " + cfg.handshakeTimeoutMs / 1000 + " s)";
                    close(st == State.VERSION
                            ? "the phone accepted the connection but did not answer" + waited + ". Stop and start the head unit server in Android Auto's developer settings, then try again"
                            : st == State.HANDSHAKE ? "the phone stopped answering during the TLS handshake" + waited
                            : "the phone did not start service discovery after authentication" + waited);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "aa-watchdog");
        t.setDaemon(true);
        t.start();
    }

    void log(String line) {
        listener.onLog(this, line);
    }

    void videoFocusRequested(boolean projected) {
        listener.onVideoFocusRequest(this, projected);
    }

    long nowUs() {
        return System.currentTimeMillis() * 1000L;
    }
}
