package me.ri3d.openauto.diag;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * H.264 decoder discovery on the API 16 MediaCodecList, plus a real "will it configure" probe.
 * API 16 cannot ask a codec for its maximum frame size, so the probe configures a decoder with
 * the requested size and reports whether that succeeded. A passed probe means the codec accepted
 * the format, not that it keeps up at that rate; that is measured during projection.
 */
public final class Decoders {
    public static final String AVC = "video/avc";
    private static final Map<String, Boolean> PROBES = new HashMap<>();

    private Decoders() {}

    /** Names of all H.264 decoders, hardware first (OMX.google.* is the software decoder). */
    @SuppressWarnings("deprecation")
    public static List<String> avcDecoders() {
        List<String> hw = new ArrayList<>(), sw = new ArrayList<>();
        try {
            int n = MediaCodecList.getCodecCount();
            for (int i = 0; i < n; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (AVC.equalsIgnoreCase(t)) {
                        (isSoftware(info.getName()) ? sw : hw).add(info.getName());
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        hw.addAll(sw);
        return hw;
    }

    private static Boolean hardware;

    /** Whether the decoder tried first is a hardware one (cached). */
    public static synchronized boolean hasHardware() {
        if (hardware == null) {
            List<String> all = avcDecoders();
            hardware = !all.isEmpty() && !isSoftware(all.get(0));
        }
        return hardware;
    }

    public static boolean isSoftware(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.contains("ffmpeg");
    }

    /** Highest AVC level reported by the first decoder, as text such as "4.1", or null. */
    @SuppressWarnings("deprecation")
    public static String maxLevel(String codecName) {
        try {
            int n = MediaCodecList.getCodecCount();
            for (int i = 0; i < n; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (!info.getName().equals(codecName)) continue;
                int best = 0;
                for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(AVC).profileLevels) {
                    best = Math.max(best, pl.level);
                }
                return levelName(best);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String levelName(int level) {
        switch (level) {
            case MediaCodecInfo.CodecProfileLevel.AVCLevel1: return "1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel1b: return "1b";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel11: return "1.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel12: return "1.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel13: return "1.3";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel2: return "2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel21: return "2.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel22: return "2.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel3: return "3";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel31: return "3.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel32: return "3.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel4: return "4";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel41: return "4.1";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel42: return "4.2";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel5: return "5";
            case MediaCodecInfo.CodecProfileLevel.AVCLevel51: return "5.1";
            default: return level == 0 ? null : "0x" + Integer.toHexString(level);
        }
    }

    /** Cached: can an H.264 decoder be configured at w x h? Slow (tens of ms); call off the UI thread. */
    public static synchronized boolean probe(int w, int h) {
        String key = w + "x" + h;
        Boolean cached = PROBES.get(key);
        if (cached != null) return cached;
        boolean ok = false;
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(AVC);
            MediaFormat f = MediaFormat.createVideoFormat(AVC, w, h);
            codec.configure(f, null, null, 0);
            ok = true;
        } catch (Throwable ignored) {
        } finally {
            if (codec != null) {
                try { codec.release(); } catch (Throwable ignored) { }
            }
        }
        PROBES.put(key, ok);
        return ok;
    }

    public static boolean probed(int w, int h) {
        return PROBES.containsKey(w + "x" + h);
    }
}
