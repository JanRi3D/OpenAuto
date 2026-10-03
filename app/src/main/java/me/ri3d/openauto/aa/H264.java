package me.ri3d.openauto.aa;

/**
 * The little H.264 bitstream knowledge the receiver needs (ITU-T H.264 §7.3.2.1.1, Annex B):
 * recognising keyframes, and changing the cropping rectangle in a sequence parameter set.
 *
 * Why cropping in the stream: the phone renders its interface into the centre of the frame and leaves
 * the video margins black. The frame cropping fields of the SPS are the standard way to tell a decoder
 * which part of the coded picture is meant for display (1080p video relies on them: 1088 coded rows,
 * 8 cropped). Every decoder reports that rectangle with its output buffers, and both ways a system
 * composes video (GPU or hardware overlay) honour it. No other cropping mechanism tried was honoured
 * by both (docs/TEST-RESULTS.md §3a, §4b).
 */
public final class H264 {
    private H264() {}

    /** Index of the first NAL header byte after a 00 00 01 start code at or after {@code from}, else -1. */
    public static int nalStart(byte[] d, int from, int end) {
        for (int i = from; i + 3 < end; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i + 3;
        }
        return -1;
    }

    /** True when the first slice in the unit belongs to an IDR picture: decoding can start here. */
    public static boolean isKeyframe(byte[] d, int off, int len) {
        int end = off + len;
        for (int p = nalStart(d, off, end); p >= 0; p = nalStart(d, p + 1, end)) {
            int type = d[p] & 0x1F;
            if (type == 5) return true;
            if (type >= 1 && type <= 4) return false;
        }
        return false;
    }

    /** What {@link #parseSps} found; pixel values. */
    public static final class Sps {
        public int width, height;                 // coded size
        public int cropLeft, cropRight, cropTop, cropBottom;
        int unitX, unitY;                         // pixels per cropping unit
        int cropFlagBit, afterCropBit;            // bit positions in the unescaped payload
    }

    /**
     * Parses the SPS payload (the bytes after the NAL header, emulation prevention already removed).
     *
     * @throws IllegalArgumentException when the data is not a parsable SPS
     */
    static Sps parseSps(byte[] rbsp) {
        Bits b = new Bits(rbsp);
        int profile = b.bits(8);
        b.bits(16); // constraint flags, level
        b.ue();     // seq_parameter_set_id
        int chroma = 1;
        boolean separatePlanes = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chroma = b.ue();
            if (chroma == 3) separatePlanes = b.bits(1) == 1;
            b.ue(); // bit_depth_luma_minus8
            b.ue(); // bit_depth_chroma_minus8
            b.bits(1); // qpprime_y_zero_transform_bypass_flag
            if (b.bits(1) == 1) { // seq_scaling_matrix_present_flag
                for (int i = 0, lists = chroma != 3 ? 8 : 12; i < lists; i++) {
                    if (b.bits(1) == 0) continue;
                    int last = 8, next = 8;
                    for (int j = 0, size = i < 6 ? 16 : 64; j < size; j++) {
                        if (next != 0) next = (last + b.se() + 256) % 256;
                        if (next != 0) last = next;
                    }
                }
            }
        }
        b.ue(); // log2_max_frame_num_minus4
        int pocType = b.ue();
        if (pocType == 0) {
            b.ue();
        } else if (pocType == 1) {
            b.bits(1);
            b.se();
            b.se();
            for (int i = 0, n = b.ue(); i < n; i++) b.se();
        }
        b.ue();    // max_num_ref_frames
        b.bits(1); // gaps_in_frame_num_value_allowed_flag
        Sps s = new Sps();
        s.width = (b.ue() + 1) * 16;
        int mapUnits = b.ue() + 1;
        int frameMbsOnly = b.bits(1);
        s.height = mapUnits * 16 * (2 - frameMbsOnly);
        if (frameMbsOnly == 0) b.bits(1); // mb_adaptive_frame_field_flag
        b.bits(1); // direct_8x8_inference_flag
        boolean planar = chroma == 0 || separatePlanes; // ChromaArrayType 0
        s.unitX = planar || chroma == 3 ? 1 : 2;
        s.unitY = (planar || chroma != 1 ? 1 : 2) * (2 - frameMbsOnly);
        s.cropFlagBit = b.pos;
        if (b.bits(1) == 1) {
            s.cropLeft = b.ue() * s.unitX;
            s.cropRight = b.ue() * s.unitX;
            s.cropTop = b.ue() * s.unitY;
            s.cropBottom = b.ue() * s.unitY;
        }
        s.afterCropBit = b.pos;
        return s;
    }

    /** Coded size and cropping of the first SPS in an Annex B unit, or null when there is none. */
    public static Sps sps(byte[] unit, int off, int len) {
        int[] range = spsRange(unit, off, len);
        if (range == null) return null;
        try {
            return parseSps(unescape(unit, range[0] + 1, range[1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the unit with its SPS changed so that half of each margin is cropped on either side, in
     * addition to what the stream already crops. Null when the unit has no SPS, the SPS cannot be
     * parsed, or the margins are not a whole number of cropping units; the caller then shows the frame
     * some other way.
     */
    public static byte[] cropSps(byte[] unit, int off, int len, int marginWidth, int marginHeight) {
        int[] range = spsRange(unit, off, len);
        if (range == null) return null;
        byte[] rbsp = unescape(unit, range[0] + 1, range[1]);
        Sps s;
        try {
            s = parseSps(rbsp);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int sideX = marginWidth / 2, sideY = marginHeight / 2;
        if (marginWidth % 2 != 0 || marginHeight % 2 != 0 || sideX % s.unitX != 0 || sideY % s.unitY != 0) return null;
        int stop = rbsp.length * 8 - 1; // rbsp_stop_one_bit: the last set bit
        while (stop >= 0 && (rbsp[stop >> 3] >> (7 - (stop & 7)) & 1) == 0) stop--;
        if (stop < s.afterCropBit) return null;

        BitWriter w = new BitWriter(rbsp.length + 8);
        w.copy(rbsp, 0, s.cropFlagBit);
        w.bit(1);
        w.ue((s.cropLeft + sideX) / s.unitX);
        w.ue((s.cropRight + sideX) / s.unitX);
        w.ue((s.cropTop + sideY) / s.unitY);
        w.ue((s.cropBottom + sideY) / s.unitY);
        w.copy(rbsp, s.afterCropBit, stop); // VUI and anything else, unchanged
        w.bit(1);
        byte[] payload = escape(w.bytes());

        int end = off + len;
        byte[] out = new byte[(range[0] + 1 - off) + payload.length + (end - range[1])];
        System.arraycopy(unit, off, out, 0, range[0] + 1 - off); // everything up to and including the NAL header
        System.arraycopy(payload, 0, out, range[0] + 1 - off, payload.length);
        System.arraycopy(unit, range[1], out, range[0] + 1 - off + payload.length, end - range[1]);
        return out;
    }

    /** {header byte index, end index} of the first SPS NAL in the unit, without trailing zero bytes; null if none. */
    private static int[] spsRange(byte[] unit, int off, int len) {
        int end = off + len;
        for (int p = nalStart(unit, off, end); p >= 0; p = nalStart(unit, p + 1, end)) {
            if ((unit[p] & 0x1F) != 7) continue;
            int next = nalStart(unit, p + 1, end);
            int stop = next < 0 ? end : next - 3;
            while (stop > p + 1 && unit[stop - 1] == 0) stop--; // 4-byte start code or trailing_zero_8bits
            return new int[]{p, stop};
        }
        return null;
    }

    /** Removes emulation prevention bytes (00 00 03 becomes 00 00). */
    private static byte[] unescape(byte[] d, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0, zeros = 0;
        for (int i = from; i < to; i++) {
            if (zeros >= 2 && d[i] == 3) {
                zeros = 0;
                continue;
            }
            zeros = d[i] == 0 ? zeros + 1 : 0;
            out[n++] = d[i];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Inserts emulation prevention bytes so the payload cannot contain a start code. */
    private static byte[] escape(byte[] d) {
        byte[] out = new byte[d.length + d.length / 2 + 2];
        int n = 0, zeros = 0;
        for (byte v : d) {
            if (zeros >= 2 && (v & 0xFF) <= 3) {
                out[n++] = 3;
                zeros = 0;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            out[n++] = v;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static final class Bits {
        final byte[] d;
        int pos;

        Bits(byte[] d) {
            this.d = d;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++, pos++) {
                if (pos >= d.length * 8) throw new IllegalArgumentException("SPS ends early");
                v = (v << 1) | (d[pos >> 3] >> (7 - (pos & 7)) & 1);
            }
            return v;
        }

        int ue() { // Exp-Golomb
            int zeros = 0;
            while (bits(1) == 0) {
                if (++zeros > 31) throw new IllegalArgumentException("bad Exp-Golomb code");
            }
            return (1 << zeros) - 1 + bits(zeros);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    private static final class BitWriter {
        private final byte[] d;
        private int pos;

        BitWriter(int capacity) {
            d = new byte[capacity];
        }

        void bit(int v) {
            if (v != 0) d[pos >> 3] |= (byte) (0x80 >> (pos & 7));
            pos++;
        }

        void ue(int v) {
            int code = v + 1, length = 32 - Integer.numberOfLeadingZeros(code);
            for (int i = 1; i < length; i++) bit(0);
            for (int i = length - 1; i >= 0; i--) bit(code >> i & 1);
        }

        void copy(byte[] from, int fromBit, int toBit) {
            for (int i = fromBit; i < toBit; i++) bit(from[i >> 3] >> (7 - (i & 7)) & 1);
        }

        /** The bits written so far, zero-padded to a whole byte. */
        byte[] bytes() {
            return java.util.Arrays.copyOf(d, (pos + 7) / 8);
        }
    }
}
