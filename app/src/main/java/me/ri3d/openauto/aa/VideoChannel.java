package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/** Video channel (3): setup, start/stop, media indications with ack, video focus (§4, §8.2 step 7). */
public final class VideoChannel extends Channel implements Media.VideoOut.Feedback {
    private final Media.VideoOut out;
    private final ProtoWriter w = new ProtoWriter(64);
    private final ProtoWriter ackW = new ProtoWriter(16); // acknowledgements also come from the decoder thread
    private final ProtoReader r = new ProtoReader();
    private volatile int session;
    private boolean started;
    private final boolean sinkAcknowledges;
    public volatile long framesReceived, bytesReceived, keyframeRequests;

    VideoChannel(Session s, Media.VideoOut out) {
        super(s, Wire.CH_VIDEO);
        this.out = out;
        sinkAcknowledges = out.feedback(this);
    }

    @Override
    void describe(ProtoWriter d) {
        Session.Config c = s.cfg;
        ProtoWriter video = new ProtoWriter(32).uint(1, c.resEnum).uint(2, c.fpsEnum).uint(3, c.marginWidth).uint(4, c.marginHeight).uint(5, c.dpi);
        ProtoWriter av = new ProtoWriter(64).uint(1, Wire.STREAM_VIDEO).message(4, video).bool(5, true);
        d.message(3, av);
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) throws IOException {
        switch (msgId) {
            case Wire.AV_SETUP_REQUEST: {
                boolean ok = out.open(s.cfg.videoWidth, s.cfg.videoHeight, s.cfg.fpsEnum == Wire.FPS_60 ? 60 : 30);
                s.log("video: setup " + s.cfg.videoWidth + "x" + s.cfg.videoHeight + " -> " + (ok ? "OK" : "FAIL"));
                w.reset().uint(1, ok ? Wire.AV_SETUP_OK : Wire.AV_SETUP_FAIL).uint(2, 1).uint(3, 0);
                s.send(id, false, Wire.AV_SETUP_RESPONSE, w);
                if (ok) sendFocus(true, false);
                break;
            }
            case Wire.AV_START: {
                r.reset(data, off, len);
                while (r.next()) {
                    if (r.field() == 1) session = r.int32(); else r.skip();
                }
                started = true;
                s.log("video: start session " + session);
                s.setState(Session.State.PROJECTING, null);
                break;
            }
            case Wire.AV_STOP:
                started = false;
                s.log("video: stop");
                break;
            case Wire.AV_MEDIA_WITH_TIMESTAMP: {
                if (len < 8) throw new IOException("video media shorter than its timestamp");
                long ts = 0;
                for (int i = 0; i < 8; i++) ts = (ts << 8) | (data[off + i] & 0xFF);
                deliver(data, off + 8, len - 8, ts);
                break;
            }
            case Wire.AV_MEDIA:
                deliver(data, off, len, 0);
                break;
            case Wire.VIDEO_FOCUS_REQUEST: {
                // focus_mode (field 2): 1 = projected, 2 = native. A real phone sends 2 when the user taps
                // Android Auto's own "Exit" entry (observed): leave the projection screen, keep the session.
                int mode = Wire.VIDEO_FOCUS_FOCUSED;
                r.reset(data, off, len);
                while (r.next()) {
                    if (r.field() == 2) mode = r.int32(); else r.skip();
                }
                boolean projected = mode != Wire.VIDEO_FOCUS_UNFOCUSED;
                s.log("video: phone requests " + (projected ? "projected" : "native") + " focus");
                sendFocus(projected, false);
                s.videoFocusRequested(projected);
                break;
            }
            default:
                s.log("video: unhandled message 0x" + Integer.toHexString(msgId));
        }
    }

    private void deliver(byte[] data, int off, int len, long ts) {
        framesReceived++;
        bytesReceived += len;
        out.write(data, off, len, ts);
        if (!sinkAcknowledges) consumed();
    }

    /**
     * Acknowledges one unit. This is the stream's flow control: the phone sends at most max_unacked
     * (we declare 1) units beyond what was acknowledged, so acknowledging when the decoder has taken
     * a unit makes the phone follow the decoder's pace instead of flooding it.
     */
    @Override
    public void consumed() {
        try {
            synchronized (ackW) {
                ackW.reset().uint(1, session).uint(2, 1);
                s.send(id, false, Wire.AV_MEDIA_ACK, ackW);
            }
        } catch (IOException e) {
            if (!s.isClosed()) s.close("video acknowledgement failed: " + e.getMessage());
        }
    }

    /**
     * The phone sends a keyframe only when a stream starts. Taking video focus away and giving it back
     * restarts the stream (observed with a real phone when the Surface was recreated).
     */
    @Override
    public synchronized void needKeyframe() {
        if (!lastFocus || s.isClosed()) return; // no Surface: the stream restarts when it comes back
        keyframeRequests++;
        s.log("video: asking the phone to restart the stream with a keyframe");
        try {
            sendFocus(false, true);
            sendFocus(true, true);
        } catch (IOException e) {
            if (!s.isClosed()) s.close("video focus change failed: " + e.getMessage());
        }
    }

    /**
     * Tells the phone whether our screen shows the projection. UNFOCUSED makes it stop streaming;
     * FOCUSED makes it resume with a fresh keyframe. Used when the Surface goes away and comes back.
     */
    public synchronized void sendFocus(boolean focused, boolean unrequested) throws IOException {
        focus.reset().uint(1, focused ? Wire.VIDEO_FOCUS_FOCUSED : Wire.VIDEO_FOCUS_UNFOCUSED).bool(2, unrequested);
        s.send(id, false, Wire.VIDEO_FOCUS_INDICATION, focus);
        lastFocus = focused;
    }

    /** Head-unit initiated focus change (Surface gone or back); sends only when the state really changes. */
    public synchronized void setFocus(boolean focused) throws IOException {
        if (focused != lastFocus) sendFocus(focused, true);
    }

    private final ProtoWriter focus = new ProtoWriter(8); // not the reader thread's writer: also used from the UI sender thread
    private boolean lastFocus;

    public boolean isStarted() {
        return started;
    }

    @Override
    void close() {
        out.close();
    }
}
