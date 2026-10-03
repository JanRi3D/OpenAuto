package me.ri3d.openauto.aa;

import java.io.IOException;

/**
 * Streaming parser for the frame layer (protocol-reference §1). Fed with arbitrary byte chunks from
 * the transport, it emits complete messages: FIRST/MIDDLE/LAST frames are concatenated per channel,
 * encrypted frame payloads are decrypted per frame.
 *
 * Channels are reassembled independently. aasdk rejects a frame for another channel in the middle
 * of a fragmented message, but a real phone (Android Auto protocol 1.7, observed 2026-10-03) sends
 * audio frames between the fragments of a large video frame, so interleaving must be accepted.
 *
 * Buffers are reused; a message larger than {@link #MAX_MESSAGE} or a malformed header raises
 * {@link IOException} and the caller ends the session rather than guessing.
 */
public final class FrameAssembler {
    public interface Sink {
        /** Called on the reader thread with the plaintext message (id + body). Copy before returning. */
        void onMessage(int channel, boolean control, byte[] data, int len) throws IOException;
    }

    public interface Decryptor {
        /** Decrypts one frame payload into {@code out} at {@code outOff}; returns the plaintext length. */
        int decrypt(byte[] in, int inOff, int inLen, byte[] out, int outOff) throws IOException;
    }

    /** Largest reassembled message accepted (an H.264 keyframe at 1080p fits comfortably). */
    public static final int MAX_MESSAGE = 2 * 1024 * 1024;
    /** Channel ids are small (9 are declared); anything above this is treated as a corrupt stream. */
    public static final int MAX_CHANNELS = 32;
    private static final int MAX_ENCRYPTED_FRAME = 0xFFFF;

    private final Sink sink;
    private final Decryptor decryptor;

    // header state
    private final byte[] head = new byte[8];
    private int headLen;
    private int frameChannel, frameFlags, framePayloadLen, headNeeded = 4;

    // current frame payload (ciphertext or plaintext as received)
    private final byte[] frame = new byte[MAX_ENCRYPTED_FRAME + 16];
    private int frameLen;

    // one message under reassembly per channel; buffers allocated on first use and kept
    private final byte[][] msg = new byte[MAX_CHANNELS][];
    private final int[] msgLen = new int[MAX_CHANNELS];
    private final boolean[] msgOpen = new boolean[MAX_CHANNELS];
    private final boolean[] msgControl = new boolean[MAX_CHANNELS];

    public FrameAssembler(Sink sink, Decryptor decryptor) {
        this.sink = sink;
        this.decryptor = decryptor;
    }

    /** Consumes {@code len} bytes; emits every message completed by them. */
    public void feed(byte[] buf, int off, int len) throws IOException {
        int end = off + len;
        while (off < end) {
            if (headLen < headNeeded) {
                int n = Math.min(headNeeded - headLen, end - off);
                System.arraycopy(buf, off, head, headLen, n);
                headLen += n;
                off += n;
                if (headLen < headNeeded) return;
                if (headNeeded == 4) {
                    frameChannel = head[0] & 0xFF;
                    frameFlags = head[1] & 0xFF;
                    framePayloadLen = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                    if (frameChannel >= MAX_CHANNELS) throw new IOException("frame for unexpected channel " + frameChannel);
                    if ((frameFlags & 0x03) == Wire.FRAME_FIRST) {
                        headNeeded = 8; // uint32 total size follows (§1.2)
                        continue;
                    }
                }
                if (headNeeded == 8) {
                    long total = ((long) (head[4] & 0xFF) << 24) | ((head[5] & 0xFF) << 16) | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
                    if (total > MAX_MESSAGE) throw new IOException("message too large: " + total);
                }
                frameLen = 0;
                if (framePayloadLen == 0) finishFrame();
                continue;
            }
            int n = Math.min(framePayloadLen - frameLen, end - off);
            System.arraycopy(buf, off, frame, frameLen, n);
            frameLen += n;
            off += n;
            if (frameLen == framePayloadLen) finishFrame();
        }
    }

    private void finishFrame() throws IOException {
        int ch = frameChannel;
        int type = frameFlags & 0x03;
        boolean encrypted = (frameFlags & Wire.FLAG_ENCRYPTED) != 0;

        if (type == Wire.FRAME_FIRST || type == Wire.FRAME_BULK) {
            if (msgOpen[ch]) throw new IOException("new message on channel " + ch + " before the previous one finished");
            msgLen[ch] = 0;
            msgOpen[ch] = true;
            msgControl[ch] = (frameFlags & Wire.FLAG_CONTROL) != 0;
        } else if (!msgOpen[ch]) {
            throw new IOException("continuation frame for channel " + ch + " without a first frame");
        }

        byte[] buf = ensure(ch, msgLen[ch] + frameLen); // ciphertext is never shorter than its plaintext
        if (encrypted) {
            if (decryptor == null) throw new IOException("encrypted frame before the TLS handshake finished");
            msgLen[ch] += decryptor.decrypt(frame, 0, frameLen, buf, msgLen[ch]);
        } else {
            System.arraycopy(frame, 0, buf, msgLen[ch], frameLen);
            msgLen[ch] += frameLen;
        }
        if (msgLen[ch] > MAX_MESSAGE) throw new IOException("message exceeds " + MAX_MESSAGE);

        headLen = 0;
        headNeeded = 4;
        frameLen = 0;

        if (type == Wire.FRAME_BULK || type == Wire.FRAME_LAST) {
            int len = msgLen[ch];
            msgLen[ch] = 0;
            msgOpen[ch] = false;
            if (len < 2) throw new IOException("message without id on channel " + ch);
            sink.onMessage(ch, msgControl[ch], buf, len);
        }
    }

    private byte[] ensure(int ch, int needed) throws IOException {
        if (needed > MAX_MESSAGE) throw new IOException("message exceeds " + MAX_MESSAGE);
        byte[] b = msg[ch];
        if (b == null) {
            b = msg[ch] = new byte[Math.max(1024, needed)];
        } else if (needed > b.length) {
            byte[] n = new byte[Math.min(MAX_MESSAGE, Math.max(b.length * 2, needed))];
            System.arraycopy(b, 0, n, 0, msgLen[ch]);
            b = msg[ch] = n;
        }
        return b;
    }

    public static int messageId(byte[] data) {
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }
}
