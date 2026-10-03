package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoWriter;

/** Input channel (1): touch and button events to the phone, binding request from it (§6). */
public final class InputChannel extends Channel {
    /** Button codes we declare; the phone binds the subset it wants. */
    static final int[] KEYCODES = {Wire.BTN_BACK, Wire.BTN_HOME, Wire.BTN_MICROPHONE_1, Wire.BTN_TOGGLE_PLAY,
            Wire.BTN_NEXT, Wire.BTN_PREV, Wire.BTN_PLAY, Wire.BTN_PAUSE};

    private final ProtoWriter w = new ProtoWriter(64);
    private final ProtoWriter touch = new ProtoWriter(32), loc = new ProtoWriter(16), btn = new ProtoWriter(16), btns = new ProtoWriter(24);
    private boolean bound;

    InputChannel(Session s) {
        super(s, Wire.CH_INPUT);
    }

    @Override
    void describe(ProtoWriter d) {
        ProtoWriter ic = new ProtoWriter(48);
        for (int k : KEYCODES) ic.uint(1, k);
        // Touch area = the content area. With margins a real phone takes touch coordinates relative to
        // the content area, 1:1 in video pixels (measured, protocol-reference §14); it accepted both this
        // declaration and the full frame size.
        ic.message(2, new ProtoWriter(8).uint(1, VideoGeometry.contentWidth(s.cfg)).uint(2, VideoGeometry.contentHeight(s.cfg)));
        d.message(4, ic);
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) throws IOException {
        if (msgId == Wire.BINDING_REQUEST) {
            bound = true;
            w.reset().uint(1, Wire.STATUS_OK);
            s.send(id, false, Wire.BINDING_RESPONSE, w);
            s.log("input: bound");
        } else {
            s.log("input: unhandled message 0x" + Integer.toHexString(msgId));
        }
    }

    /**
     * Single-pointer touch in content-area pixel coordinates (see {@link VideoGeometry}). {@code action}
     * is one of Wire.TOUCH_PRESS / TOUCH_RELEASE / TOUCH_DRAG. Writes to the transport: never call it
     * on the Android UI thread (NetworkOnMainThreadException on modern Android).
     */
    public void touch(int action, int x, int y) {
        loc.reset().uint(1, Math.max(0, x)).uint(2, Math.max(0, y)).uint(3, 0);
        touch.reset().message(1, loc).uint(2, 0).uint(3, action);
        w.reset().uint(1, s.nowUs()).message(3, touch);
        trySend();
    }

    public void button(int code, boolean pressed) {
        btn.reset().uint(1, code).bool(2, pressed).uint(3, 0).bool(4, false);
        btns.reset().message(1, btn);
        w.reset().uint(1, s.nowUs()).message(4, btns);
        trySend();
    }

    private synchronized void trySend() {
        try {
            s.send(id, false, Wire.INPUT_EVENT, w);
        } catch (IOException e) {
            s.close("input send failed: " + e.getMessage());
        }
    }

    public boolean isBound() {
        return bound;
    }
}
