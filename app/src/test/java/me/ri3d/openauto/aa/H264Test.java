package me.ri3d.openauto.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class H264Test {
    /** SPS and PPS as a real phone (Samsung SM-S948B) sent them for 800x480: Baseline, level 3.1, no cropping. */
    private static final byte[] PHONE = hex("00000001" + "6742801fda0320f69a8080808368509a80" + "00000001" + "68ce06e2");
    /** x264 800x480 Baseline with VUI: contains emulation prevention bytes (00 00 03). */
    private static final byte[] X264 = hex("00000001" + "6742c01fd900c83db0110000030001000003003c0f183248" + "00000001" + "68cb83cb20");

    @Test
    public void readsCodedSizeOfRealStreams() {
        H264.Sps phone = H264.sps(PHONE, 0, PHONE.length);
        assertEquals(800, phone.width);
        assertEquals(480, phone.height);
        assertEquals(0, phone.cropTop + phone.cropBottom + phone.cropLeft + phone.cropRight);
        H264.Sps x264 = H264.sps(X264, 0, X264.length);
        assertEquals(800, x264.width);
        assertEquals(480, x264.height);
    }

    @Test
    public void cropsTheMarginsAndKeepsEverythingElse() {
        for (byte[] unit : new byte[][]{PHONE, X264}) {
            byte[] out = H264.cropSps(unit, 0, unit.length, 0, 180); // the 1920x720 radio at 800x480
            assertNotNull(out);
            H264.Sps s = H264.sps(out, 0, out.length);
            assertEquals(800, s.width);
            assertEquals(480, s.height);
            assertEquals(90, s.cropTop);
            assertEquals(90, s.cropBottom);
            assertEquals(0, s.cropLeft + s.cropRight);
            // the PPS that follows the SPS is untouched, and no start code appeared inside the SPS
            int pps = lastNal(out), ppsBefore = lastNal(unit);
            assertArrayEquals(java.util.Arrays.copyOfRange(unit, ppsBefore, unit.length), java.util.Arrays.copyOfRange(out, pps, out.length));
            assertEquals(2, countNals(out));
            // cropping again adds to what is already cropped
            byte[] twice = H264.cropSps(out, 0, out.length, 160, 20);
            H264.Sps t = H264.sps(twice, 0, twice.length);
            assertEquals(100, t.cropTop);
            assertEquals(100, t.cropBottom);
            assertEquals(80, t.cropLeft);
            assertEquals(80, t.cropRight);
        }
    }

    @Test
    public void zeroMarginsOnlySetTheFlag() {
        byte[] zero = H264.cropSps(X264, 0, X264.length, 0, 0); // flag set, all four offsets zero: 4 bits more
        H264.Sps z = H264.sps(zero, 0, zero.length);
        assertEquals(800, z.width);
        assertEquals(0, z.cropTop + z.cropBottom + z.cropLeft + z.cropRight);
        assertTrue(zero.length - X264.length >= 0 && zero.length - X264.length <= 1);
    }

    /**
     * Opt-in: rewrites every SPS of an Annex B file so a real decoder can judge the result, e.g.
     * {@code H264_IN=in.h264 H264_OUT=out.h264 H264_MARGIN_H=180 gradlew :app:testDebugUnitTest --tests '*H264Test*' --rerun}
     * followed by {@code ffprobe out.h264} (expected: the cropped size, same frame rate, no errors).
     */
    @Test
    public void rewriteFile() throws Exception {
        String in = System.getenv("H264_IN"), out = System.getenv("H264_OUT");
        org.junit.Assume.assumeTrue("H264_IN/H264_OUT not set", in != null && out != null);
        int marginW = Integer.parseInt(System.getenv("H264_MARGIN_W") == null ? "0" : System.getenv("H264_MARGIN_W"));
        int marginH = Integer.parseInt(System.getenv("H264_MARGIN_H") == null ? "0" : System.getenv("H264_MARGIN_H"));
        byte[] d = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(in));
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        int from = 0, rewritten = 0;
        for (int p = H264.nalStart(d, 0, d.length); p >= 0; p = H264.nalStart(d, p + 1, d.length)) {
            if ((d[p] & 0x1F) != 7) continue;
            int next = H264.nalStart(d, p + 1, d.length);
            int end = next < 0 ? d.length : next - 3;
            byte[] sps = H264.cropSps(d, p - 3, end - (p - 3), marginW, marginH);
            assertNotNull(sps);
            result.write(d, from, p - 3 - from);
            result.write(sps);
            from = end;
            rewritten++;
        }
        result.write(d, from, d.length - from);
        java.nio.file.Files.write(java.nio.file.Paths.get(out), result.toByteArray());
        System.out.println("rewrote " + rewritten + " SPS into " + out);
    }

    @Test
    public void refusesWhatItCannotCropExactly() {
        assertNull("odd margin", H264.cropSps(PHONE, 0, PHONE.length, 0, 181));
        assertNull("half margin not a whole chroma row", H264.cropSps(PHONE, 0, PHONE.length, 0, 110));
        byte[] ppsOnly = hex("0000000168ce06e2");
        assertNull("no SPS", H264.cropSps(ppsOnly, 0, ppsOnly.length, 0, 180));
        byte[] truncated = hex("000000016742801fda");
        assertNull("truncated SPS", H264.cropSps(truncated, 0, truncated.length, 0, 180));
    }

    @Test
    public void recognisesKeyframes() {
        byte[] idr = hex("0000000165888400");
        byte[] p = hex("00000001419a0000");
        byte[] spsPpsIdr = hex("00000001" + "6742801fda0320f69a8080808368509a80" + "00000001" + "68ce06e2" + "000001" + "65888400");
        assertTrue(H264.isKeyframe(idr, 0, idr.length));
        assertFalse(H264.isKeyframe(p, 0, p.length));
        assertTrue(H264.isKeyframe(spsPpsIdr, 0, spsPpsIdr.length));
        assertFalse("parameter sets alone are not a picture", H264.isKeyframe(PHONE, 0, PHONE.length));
    }

    private static int lastNal(byte[] d) {
        int last = -1;
        for (int p = H264.nalStart(d, 0, d.length); p >= 0; p = H264.nalStart(d, p + 1, d.length)) last = p;
        return last;
    }

    private static int countNals(byte[] d) {
        int n = 0;
        for (int p = H264.nalStart(d, 0, d.length); p >= 0; p = H264.nalStart(d, p + 1, d.length)) n++;
        return n;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
