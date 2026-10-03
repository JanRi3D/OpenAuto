package me.ri3d.openauto.diag;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Measures how fast this device runs the two record ciphers the TLS session can use, in the same
 * shape TLS uses them (one init + one 8 KiB record at a time). On a real head unit (4x Cortex-A7,
 * Android 4.2) the session's reader thread was found saturating a core on decryption, so this number
 * decides whether a resolution is usable: 480p30 with music needs roughly 0.4 MB/s.
 */
public final class CryptoBench {
    private CryptoBench() {}

    /** Returns {AES-128-GCM, ChaCha20-Poly1305} throughput in MB/s. Takes about a second; call off the UI thread. */
    public static double[] run() {
        return new double[]{
                bench(GCMBlockCipher.newInstance(AESEngine.newInstance()), 16),
                bench(new ChaCha20Poly1305(), 32)};
    }

    private static double bench(AEADCipher cipher, int keyLen) {
        byte[] key = new byte[keyLen], nonce = new byte[12], aad = new byte[13];
        byte[] in = new byte[8192], out = new byte[8192 + 32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 1);
        KeyParameter kp = new KeyParameter(key);
        long bytes = 0, start = 0;
        long warmUntil = System.nanoTime() + 150_000_000L, end = warmUntil + 400_000_000L;
        try {
            while (true) {
                long now = System.nanoTime();
                if (now >= end) return bytes / ((now - start) / 1e9) / 1e6;
                if (start == 0 && now >= warmUntil) { // warm-up over: start counting
                    start = now;
                    bytes = 0;
                }
                nonce[11]++;
                cipher.init(true, new AEADParameters(kp, 128, nonce, aad));
                int n = cipher.processBytes(in, 0, in.length, out, 0);
                cipher.doFinal(out, n);
                bytes += in.length;
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
