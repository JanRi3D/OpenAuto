package me.ri3d.openauto.ui;

import android.content.Context;
import android.graphics.Typeface;

/** Barlow (SIL OFL) loaded once from assets; API 16 has no font resources. */
public final class Fonts {
    public static final int REGULAR = 0, MEDIUM = 1, SEMIBOLD = 2, CONDENSED = 3;
    private static final String[] FILES = {
            "fonts/Barlow-Regular.ttf", "fonts/Barlow-Medium.ttf",
            "fonts/Barlow-SemiBold.ttf", "fonts/BarlowSemiCondensed-SemiBold.ttf"};
    private static final Typeface[] CACHE = new Typeface[FILES.length];

    private Fonts() {}

    public static Typeface get(Context ctx, int which) {
        if (which < 0 || which >= FILES.length) which = REGULAR;
        Typeface t = CACHE[which];
        if (t == null) {
            try {
                t = Typeface.createFromAsset(ctx.getApplicationContext().getAssets(), FILES[which]);
            } catch (RuntimeException e) {
                // Missing or unreadable asset: fall back to the platform family, never crash the launcher.
                t = which == CONDENSED ? Typeface.create("sans-serif-condensed", Typeface.BOLD)
                        : Typeface.create("sans-serif", which == REGULAR ? Typeface.NORMAL : Typeface.BOLD);
            }
            CACHE[which] = t;
        }
        return t;
    }
}
