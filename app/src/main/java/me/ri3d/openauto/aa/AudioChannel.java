package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/** Audio output channel (media 48k stereo, speech/system 16k mono): PCM indications with ack (§4, §8.2 step 8). */
public final class AudioChannel extends Channel {
    private final Media.AudioOut out;
    private final int audioType, sampleRate, channels;
    private final ProtoWriter w = new ProtoWriter(64);
    private final ProtoReader r = new ProtoReader();
    private int session = -1;
    public volatile long packets;

    AudioChannel(Session s, int channelId, int audioType, int sampleRate, int channels, Media.AudioOut out) {
        super(s, channelId);
        this.audioType = audioType;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.out = out;
    }

    @Override
    void describe(ProtoWriter d) {
        ProtoWriter cfg = new ProtoWriter(16).uint(1, sampleRate).uint(2, 16).uint(3, channels);
        ProtoWriter av = new ProtoWriter(48).uint(1, Wire.STREAM_AUDIO).uint(2, audioType).message(3, cfg).bool(5, true);
        d.message(3, av);
    }

    @Override
    protected boolean onOpen() {
        return out.open(sampleRate, channels);
    }

    @Override
    protected void handle(int msgId, byte[] data, int off, int len) throws IOException {
        switch (msgId) {
            case Wire.AV_SETUP_REQUEST:
                w.reset().uint(1, Wire.AV_SETUP_OK).uint(2, 1).uint(3, 0);
                s.send(id, false, Wire.AV_SETUP_RESPONSE, w);
                break;
            case Wire.AV_START:
                r.reset(data, off, len);
                while (r.next()) {
                    if (r.field() == 1) session = r.int32(); else r.skip();
                }
                break;
            case Wire.AV_STOP:
                session = -1;
                out.stop();
                break;
            case Wire.AV_MEDIA_WITH_TIMESTAMP:
                if (len < 8) throw new IOException("audio media shorter than its timestamp");
                deliver(data, off + 8, len - 8);
                break;
            case Wire.AV_MEDIA:
                deliver(data, off, len);
                break;
            default:
                s.log(Wire.channelName(id) + ": unhandled message 0x" + Integer.toHexString(msgId));
        }
    }

    private void deliver(byte[] data, int off, int len) throws IOException {
        packets++;
        out.write(data, off, len);
        w.reset().uint(1, session).uint(2, 1);
        s.send(id, false, Wire.AV_MEDIA_ACK, w);
    }

    public void setDucked(boolean ducked) {
        out.setDucked(ducked);
    }

    @Override
    void close() {
        out.close();
    }
}
