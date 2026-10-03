package me.ri3d.openauto.aa;

import java.io.IOException;

import me.ri3d.openauto.transport.Transport;

/**
 * Builds frames (protocol-reference §1) and writes them to the transport. Messages at or above
 * {@link Wire#MAX_FRAME_PAYLOAD} plaintext bytes are split into FIRST/MIDDLE/LAST frames; each frame
 * is encrypted separately. One reusable output buffer; calls are serialised by the caller (Session).
 */
public final class FrameWriter {
    public interface Encryptor {
        /** Encrypts {@code len} plaintext bytes into {@code out} at {@code outOff}; returns ciphertext length. */
        int encrypt(byte[] in, int inOff, int len, byte[] out, int outOff) throws IOException;
    }

    private final Transport transport;
    private final byte[] out = new byte[8 + 0xFFFF];
    private final byte[] payload = new byte[Wire.MAX_FRAME_PAYLOAD + 2];

    public FrameWriter(Transport transport) {
        this.transport = transport;
    }

    /**
     * Sends one message: {@code messageId} followed by {@code bodyLen} bytes of {@code body}.
     * {@code encryptor} null means a plain frame.
     */
    public void send(int channel, boolean controlFlag, int messageId, byte[] body, int bodyOff, int bodyLen,
                     Encryptor encryptor) throws IOException {
        int total = bodyLen + 2;
        int flagsBase = (controlFlag ? Wire.FLAG_CONTROL : 0) | (encryptor != null ? Wire.FLAG_ENCRYPTED : 0);
        if (total <= Wire.MAX_FRAME_PAYLOAD) {
            payload[0] = (byte) (messageId >> 8);
            payload[1] = (byte) messageId;
            System.arraycopy(body, bodyOff, payload, 2, bodyLen);
            writeFrame(channel, flagsBase | Wire.FRAME_BULK, payload, total, total, encryptor);
            return;
        }
        // Split at MAX_FRAME_PAYLOAD plaintext bytes (§1.3). The id lives in the first chunk.
        int sent = 0;
        boolean first = true;
        while (sent < total) {
            int chunk = Math.min(Wire.MAX_FRAME_PAYLOAD, total - sent);
            int p = 0;
            if (first) {
                payload[0] = (byte) (messageId >> 8);
                payload[1] = (byte) messageId;
                p = 2;
            }
            int fromBody = chunk - p;
            System.arraycopy(body, bodyOff + (sent + p) - 2, payload, p, fromBody);
            int type = first ? Wire.FRAME_FIRST : (sent + chunk == total ? Wire.FRAME_LAST : Wire.FRAME_MIDDLE);
            writeFrame(channel, flagsBase | type, payload, chunk, total, encryptor);
            sent += chunk;
            first = false;
        }
    }

    private void writeFrame(int channel, int flags, byte[] plain, int plainLen, int total, Encryptor enc) throws IOException {
        int type = flags & 0x03;
        int headerLen = type == Wire.FRAME_FIRST ? 8 : 4;
        int len;
        if (enc != null) {
            len = enc.encrypt(plain, 0, plainLen, out, headerLen);
        } else {
            System.arraycopy(plain, 0, out, headerLen, plainLen);
            len = plainLen;
        }
        if (len > 0xFFFF) throw new IOException("frame payload too large: " + len);
        out[0] = (byte) channel;
        out[1] = (byte) flags;
        out[2] = (byte) (len >> 8);
        out[3] = (byte) len;
        if (headerLen == 8) {
            out[4] = (byte) (total >>> 24);
            out[5] = (byte) (total >>> 16);
            out[6] = (byte) (total >>> 8);
            out[7] = (byte) total;
        }
        transport.write(out, headerLen + len);
    }
}
