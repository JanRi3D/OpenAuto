package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.proto.ProtoWriter;

/** One service channel. Handles the CHANNEL_OPEN handshake; subclasses handle their own messages. */
abstract class Channel {
    protected final Session s;
    final int id;
    private final ProtoWriter w = new ProtoWriter(32);

    Channel(Session s, int id) {
        this.s = s;
        this.id = id;
    }

    final void onMessage(int msgId, byte[] data, int off, int len) throws IOException {
        if (msgId == Wire.CHANNEL_OPEN_REQUEST) {
            boolean ok = onOpen();
            s.log(Wire.channelName(id) + ": open -> " + (ok ? "OK" : "FAIL"));
            w.reset().uint(1, ok ? Wire.STATUS_OK : Wire.STATUS_FAIL);
            s.send(id, true, Wire.CHANNEL_OPEN_RESPONSE, w);
            return;
        }
        handle(msgId, data, off, len);
    }

    /** Called for CHANNEL_OPEN_REQUEST; true answers OK. */
    protected boolean onOpen() {
        return true;
    }

    protected abstract void handle(int msgId, byte[] data, int off, int len) throws IOException;

    /** Writes this channel's ChannelDescriptor fields (channel_id is written by the caller). */
    abstract void describe(ProtoWriter d);

    void close() {}
}
