package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/** Microphone channel (AV_INPUT, 7): the phone opens/closes capture; we stream 16 kHz mono PCM (§8.2 step 9). */
public final class MicChannel extends Channel implements Media.MicIn.Listener {
    private static final int RATE = 16000;
    private final Media.MicIn mic;
    private final ProtoWriter w = new ProtoWriter(32);
    private final ProtoReader r = new ProtoReader();
    private byte[] body = new byte[8 + 4096];
    private volatile boolean open;
    public volatile long packetsSent;

    MicChannel(Session s, Media.MicIn mic) {
        super(s, Wire.CH_AV_INPUT);
        this.mic = mic;
    }

    @Override
    void describe(ProtoWriter d) {
        ProtoWriter cfg = new ProtoWriter(16).uint(1, RATE).uint(2, 16).uint(3, 1);
        d.message(5, new ProtoWriter(32).uint(1, Wire.STREAM_AUDIO).message(2, cfg));
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) throws IOException {
        switch (msgId) {
            case Wire.AV_SETUP_REQUEST:
                w.reset().uint(1, Wire.AV_SETUP_OK).uint(2, 1).uint(3, 0);
                s.send(id, false, Wire.AV_SETUP_RESPONSE, w);
                break;
            case Wire.AV_INPUT_OPEN_REQUEST: {
                boolean wantOpen = false;
                r.reset(data, off, len);
                while (r.next()) {
                    if (r.field() == 1) wantOpen = r.bool(); else r.skip();
                }
                boolean ok;
                if (wantOpen) {
                    ok = mic.open(RATE, this);
                    open = ok;
                } else {
                    mic.close();
                    open = false;
                    ok = true;
                }
                s.log("mic: " + (wantOpen ? "open" : "close") + " -> " + (ok ? "OK" : "FAIL"));
                w.reset().uint(1, 0).uint(2, ok ? 0 : 1);
                s.send(id, false, Wire.AV_INPUT_OPEN_RESPONSE, w);
                break;
            }
            case Wire.AV_MEDIA_ACK:
                break; // flow control not applied by the reference implementations either
            default:
                s.log("mic: unhandled message 0x" + Integer.toHexString(msgId));
        }
    }

    /** Called on the capture thread. */
    @Override
    public void onMicData(byte[] pcm, int len, long timestampUs) {
        if (!open) return;
        if (body.length < 8 + len) body = new byte[8 + len];
        for (int i = 0; i < 8; i++) body[i] = (byte) (timestampUs >>> (56 - 8 * i));
        System.arraycopy(pcm, 0, body, 8, len);
        try {
            s.send(id, false, Wire.AV_MEDIA_WITH_TIMESTAMP, body, 8 + len);
            packetsSent++;
        } catch (IOException e) {
            s.close("microphone send failed: " + e.getMessage());
        }
    }

    public boolean isOpen() {
        return open;
    }

    @Override
    void close() {
        open = false;
        mic.close();
    }
}
