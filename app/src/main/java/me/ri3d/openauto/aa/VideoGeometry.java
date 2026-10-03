package me.ri3d.openauto.aa;

/**
 * Fits the phone's fixed video frames (800x480, 1280x720, 1920x1080) to a display of another shape
 * without stretching. Observed with a real phone (protocol-reference §14): when the video config
 * carries margins, the phone lays its UI out in the centred area {@code (width - marginWidth) x
 * (height - marginHeight)}, leaves the rest black, and expects touch coordinates relative to that
 * content area.
 *
 * The head unit shows only the content area: the video view gets the content's shape and the decoder
 * crops the frame to it (centre crop). The view never extends beyond the screen: a real head unit's
 * hardware composer squeezed an oversized, partly off-screen video layer instead of clipping it.
 */
public final class VideoGeometry {
    private VideoGeometry() {}

    /**
     * Margins {width, height} that give the content area the aspect ratio of {@code areaW:areaH}.
     * Content never shrinks below half the frame; a more extreme display is letterboxed instead.
     */
    public static int[] margins(int videoW, int videoH, int areaW, int areaH) {
        if (areaW <= 0 || areaH <= 0) return new int[]{0, 0};
        long wide = (long) areaW * videoH, tall = (long) areaH * videoW;
        if (wide == tall) return new int[]{0, 0};
        // Margins are multiples of 4: each side is then a whole number of H.264 cropping units (2 px).
        if (wide > tall) { // display is wider than the frame: give up rows
            int contentH = Math.max(even(Math.round((float) videoW * areaH / areaW)), even(videoH / 2));
            return new int[]{0, (videoH - contentH) & ~3};
        }
        int contentW = Math.max(even(Math.round((float) videoH * areaW / areaH)), even(videoW / 2));
        return new int[]{(videoW - contentW) & ~3, 0};
    }

    private static int even(int v) {
        return v & ~1;
    }

    public static int contentWidth(Session.Config c) {
        return c.videoWidth - c.marginWidth;
    }

    public static int contentHeight(Session.Config c) {
        return c.videoHeight - c.marginHeight;
    }

    /** Size {width, height} of the largest view with the content's shape that fits the display area. */
    public static int[] fit(Session.Config c, int areaW, int areaH) {
        int cw = contentWidth(c), ch = contentHeight(c);
        float s = Math.min(areaW / (float) cw, areaH / (float) ch);
        return new int[]{Math.min(areaW, Math.round(cw * s)), Math.min(areaH, Math.round(ch * s))};
    }

    /** Converts a touch on the video view (which shows exactly the content area) to content coordinates. */
    public static void toContent(Session.Config c, int viewW, int viewH, float x, float y, int[] out) {
        int cw = contentWidth(c), ch = contentHeight(c);
        out[0] = Math.max(0, Math.min(cw - 1, Math.round(x * cw / Math.max(1, viewW))));
        out[1] = Math.max(0, Math.min(ch - 1, Math.round(y * ch / Math.max(1, viewH))));
    }
}
