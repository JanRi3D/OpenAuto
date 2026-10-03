package me.ri3d.openauto.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import me.ri3d.openauto.transport.Transport;

/** FrameWriter -> bytes -> FrameAssembler, including splitting, "encryption" and malformed input. */
public class FrameCodecTest {

    /** Captures written frames as one byte stream. */
    static final class Capture implements Transport {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        @Override public int read(byte[] buf, int maxLen) { return -1; }
        @Override public void write(byte[] buf, int len) { out.write(buf, 0, len); }
        @Override public String describe() { return "capture"; }
        @Override public void close() { }
    }

    static final class Received {
        final int channel; final boolean control; final byte[] data;
        Received(int channel, boolean control, byte[] data, int len) {
            this.channel = channel; this.control = control; this.data = java.util.Arrays.copyOf(data, len);
        }
    }

    /** Toy cipher: 3-byte header + XOR, so ciphertext length differs from plaintext length. */
    static final FrameWriter.Encryptor XOR_ENC = (in, inOff, len, out, outOff) -> {
        out[outOff] = 'X'; out[outOff + 1] = (byte) (len >> 8); out[outOff + 2] = (byte) len;
        for (int i = 0; i < len; i++) out[outOff + 3 + i] = (byte) (in[inOff + i] ^ 0x5A);
        return len + 3;
    };
    static final FrameAssembler.Decryptor XOR_DEC = (in, inOff, inLen, out, outOff) -> {
        if (in[inOff] != 'X') throw new IOException("bad toy header");
        int len = ((in[inOff + 1] & 0xFF) << 8) | (in[inOff + 2] & 0xFF);
        for (int i = 0; i < len; i++) out[outOff + i] = (byte) (in[inOff + 3 + i] ^ 0x5A);
        return len;
    };

    private static List<Received> feed(byte[] stream, int chunk, FrameAssembler.Decryptor dec) throws IOException {
        List<Received> got = new ArrayList<>();
        FrameAssembler a = new FrameAssembler((ch, ctl, data, len) -> got.add(new Received(ch, ctl, data, len)), dec);
        for (int off = 0; off < stream.length; off += chunk) a.feed(stream, off, Math.min(chunk, stream.length - off));
        return got;
    }

    @Test
    public void versionRequestMatchesReferenceBytes() throws IOException {
        Capture t = new Capture();
        new FrameWriter(t).send(0, false, Wire.VERSION_REQUEST, new byte[]{0, 1, 0, 1}, 0, 4, null);
        // protocol-reference §2.2: 00 03 00 06 00 01 00 01 00 01
        assertArrayEquals(new byte[]{0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01}, t.out.toByteArray());
    }

    @Test
    public void smallAndLargeMessagesRoundTripAtAnyChunking() throws IOException {
        Random rnd = new Random(7);
        byte[] small = new byte[100], large = new byte[100_000], exact = new byte[Wire.MAX_FRAME_PAYLOAD - 2];
        rnd.nextBytes(small); rnd.nextBytes(large); rnd.nextBytes(exact);
        Capture t = new Capture();
        FrameWriter w = new FrameWriter(t);
        w.send(3, false, 0x0000, small, 0, small.length, null);
        w.send(3, false, 0x0000, large, 0, large.length, null);
        w.send(4, true, 0x0008, exact, 0, exact.length, null);
        byte[] stream = t.out.toByteArray();
        for (int chunk : new int[]{1, 3, 7, 512, 4097, stream.length}) {
            List<Received> got = feed(stream, chunk, null);
            assertEquals("chunk " + chunk, 3, got.size());
            assertEquals(3, got.get(0).channel);
            assertEquals(0x0000, FrameAssembler.messageId(got.get(0).data));
            assertArrayEquals(small, java.util.Arrays.copyOfRange(got.get(0).data, 2, got.get(0).data.length));
            assertArrayEquals(large, java.util.Arrays.copyOfRange(got.get(1).data, 2, got.get(1).data.length));
            assertTrue(got.get(2).control);
            assertEquals(4, got.get(2).channel);
            assertArrayEquals(exact, java.util.Arrays.copyOfRange(got.get(2).data, 2, got.get(2).data.length));
        }
        // the large message must have been split into FIRST/MIDDLE/LAST frames of <= 16 KiB plaintext
        assertEquals(Wire.FRAME_BULK, stream[1] & 0x03);
        int firstLen = 4 + ((stream[2] & 0xFF) << 8 | (stream[3] & 0xFF));
        assertEquals(Wire.FRAME_FIRST, stream[firstLen + 1] & 0x03);
    }

    @Test
    public void encryptedFramesAreDecryptedPerFrame() throws IOException {
        byte[] body = new byte[40_000];
        new Random(1).nextBytes(body);
        Capture t = new Capture();
        new FrameWriter(t).send(5, false, 0x8003, body, 0, body.length, XOR_ENC);
        byte[] stream = t.out.toByteArray();
        assertEquals(Wire.FLAG_ENCRYPTED | Wire.FRAME_FIRST, stream[1] & 0xFF);
        List<Received> got = feed(stream, 1000, XOR_DEC);
        assertEquals(1, got.size());
        assertEquals(0x8003, FrameAssembler.messageId(got.get(0).data));
        assertArrayEquals(body, java.util.Arrays.copyOfRange(got.get(0).data, 2, got.get(0).data.length));
    }

    @Test
    public void truncatedStreamEmitsNothing() throws IOException {
        byte[] body = new byte[5000];
        Capture t = new Capture();
        new FrameWriter(t).send(3, false, 0x0001, body, 0, body.length, null);
        byte[] stream = t.out.toByteArray();
        for (int cut = 1; cut < stream.length; cut += 97) {
            assertEquals("cut " + cut, 0, feed(java.util.Arrays.copyOf(stream, cut), 64, null).size());
        }
    }

    @Test
    public void rejectsMalformedFrames() {
        // FIRST frame announcing a 3 MiB message
        byte[] huge = {3, 0x01, 0x00, 0x10, 0x00, 0x30, 0x00, 0x00};
        expectIOException(huge, "message too large");
        // LAST frame without a FIRST
        byte[] orphan = {3, 0x02, 0x00, 0x02, 0x00, 0x01};
        expectIOException(orphan, "without a first frame");
        // BULK frame with a 1-byte payload: no room for a message id
        byte[] tiny = {3, 0x03, 0x00, 0x01, 0x42};
        expectIOException(tiny, "without id");
        // a second FIRST on a channel whose message is still open
        byte[] reopened = {3, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x08, 0, 0, 1, 2, 3, 0x01, 0x00, 0x02, 0, 0, 0, 4, 0, 1};
        expectIOException(reopened, "before the previous one finished");
        // channel id far outside the declared range
        byte[] wild = {(byte) 200, 0x03, 0x00, 0x02, 0, 1};
        expectIOException(wild, "unexpected channel");
    }

    /**
     * Regression for the first real-phone session: the phone sent a media-audio frame (channel 4)
     * between the fragments of a video message (channel 3). Both messages must arrive intact.
     */
    @Test
    public void interleavedChannelsAreReassembledIndependently() throws IOException {
        byte[] video = new byte[40_000], audio = new byte[20_000];
        new Random(11).nextBytes(video);
        new Random(12).nextBytes(audio);
        Capture tv = new Capture(), ta = new Capture();
        new FrameWriter(tv).send(3, false, 0x0000, video, 0, video.length, XOR_ENC);
        new FrameWriter(ta).send(4, false, 0x0000, audio, 0, audio.length, XOR_ENC);
        List<byte[]> vf = splitFrames(tv.out.toByteArray()), af = splitFrames(ta.out.toByteArray());
        assertEquals(3, vf.size());
        assertEquals(2, af.size());
        ByteArrayOutputStream mixed = new ByteArrayOutputStream();
        mixed.write(vf.get(0)); mixed.write(af.get(0)); mixed.write(vf.get(1)); mixed.write(af.get(1)); mixed.write(vf.get(2));
        for (int chunk : new int[]{1, 777, 100_000}) {
            List<Received> got = feed(mixed.toByteArray(), chunk, XOR_DEC);
            assertEquals(2, got.size());
            assertEquals(4, got.get(0).channel); // audio completes first
            assertArrayEquals(audio, java.util.Arrays.copyOfRange(got.get(0).data, 2, got.get(0).data.length));
            assertEquals(3, got.get(1).channel);
            assertArrayEquals(video, java.util.Arrays.copyOfRange(got.get(1).data, 2, got.get(1).data.length));
        }
    }

    /** Cuts a captured stream back into its frames using the header length fields. */
    private static List<byte[]> splitFrames(byte[] s) {
        List<byte[]> frames = new ArrayList<>();
        int p = 0;
        while (p < s.length) {
            int header = (s[p + 1] & 0x03) == Wire.FRAME_FIRST ? 8 : 4;
            int len = header + (((s[p + 2] & 0xFF) << 8) | (s[p + 3] & 0xFF));
            frames.add(java.util.Arrays.copyOfRange(s, p, p + len));
            p += len;
        }
        return frames;
    }

    private static void expectIOException(byte[] stream, String fragment) {
        try {
            feed(stream, stream.length, null);
            fail("accepted malformed stream expecting '" + fragment + "'");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }
}
