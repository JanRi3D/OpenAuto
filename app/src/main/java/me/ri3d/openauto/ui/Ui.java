package me.ri3d.openauto.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Tiny view helpers shared by the launcher and settings screens. */
public final class Ui {
    private Ui() {}

    public static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Status dot: a 2dp ring with an optional fill (transparent = idle, filled = active). */
    public static void dot(View v, int ring, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(v.getContext(), 2), ring);
        d.setColor(fill);
        v.setBackground(d);
    }

    /** Thin circular ring used around the tile arrows. */
    public static void ring(View v, int color) {
        dot(v, color, 0);
    }
}
