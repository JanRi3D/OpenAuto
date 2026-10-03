package me.ri3d.openauto.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ResampleTest {

    @Test
    public void downsamples48kTo16kPreservingAmplitudeAndLength() {
        int inRate = 48000, outRate = 16000, inSamples = 480;
        byte[] in = new byte[inSamples * 2];
        for (int i = 0; i < inSamples; i++) {
            short v = (short) (10000 * Math.sin(2 * Math.PI * 440 * i / inRate));
            in[2 * i] = (byte) v;
            in[2 * i + 1] = (byte) (v >> 8);
        }
        byte[] out = new byte[inSamples * 2];
        int n = MicInput.resample(in, in.length, inRate, out, outRate);
        assertEquals(160 * 2, n);
        int peak = 0;
        for (int i = 0; i < n / 2; i++) {
            int s = (short) ((out[2 * i] & 0xFF) | (out[2 * i + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
        }
        assertTrue("peak " + peak, peak > 9000 && peak <= 10000);
    }

    @Test
    public void identityRateCopies() {
        byte[] in = {1, 2, 3, 4, 5, 6};
        byte[] out = new byte[6];
        assertEquals(6, MicInput.resample(in, 6, 16000, out, 16000));
        assertEquals(3, out[2]);
        assertEquals(6, out[5]);
    }
}
