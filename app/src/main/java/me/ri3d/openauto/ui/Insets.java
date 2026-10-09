package me.ri3d.openauto.ui;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import me.ri3d.openauto.settings.Prefs;

/**
 * System bars and window insets for every screen, as Settings › General › System bars says:
 * "notch" hides both bars and keeps content out of a camera cutout, "status" hides only the status
 * bar, "full" hides both bars and draws under the cutout, "normal" shows both bars. Android 4.1–4.3
 * can only hide the status bar (a hidden navigation bar returns on the first touch there).
 * Newer API calls live in nested classes so old runtimes never load them.
 */
public final class Insets {
    private Insets() {}

    /** Adds the visible system bars and, unless the mode is "full", the display cutout to the view's own padding. */
    public static void fit(View v) {
        if (Build.VERSION.SDK_INT >= 21) Api21.fit(v);
    }

    /** Shows or hides the system bars; call from onCreate and again whenever the window regains focus. */
    public static void systemBars(Window w) {
        String mode = new Prefs(w.getContext()).systemBars();
        // The theme starts every window without a status bar; only "normal" brings it back.
        if ("normal".equals(mode)) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (Build.VERSION.SDK_INT >= 19) Api19.systemBars(w, mode);
        if (Build.VERSION.SDK_INT >= 21) Api21.refit(w); // "notch" and "full" differ only in fit()'s padding
    }

    @TargetApi(21)
    private static final class Api21 {
        static void fit(View v) {
            final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                @SuppressWarnings("deprecation")
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    boolean cutout = !"full".equals(new Prefs(view.getContext()).systemBars());
                    int il, it, ir, ib;
                    if (Build.VERSION.SDK_INT >= 30) {
                        int types = WindowInsets.Type.systemBars() | (cutout ? WindowInsets.Type.displayCutout() : 0);
                        android.graphics.Insets i = insets.getInsets(types);
                        il = i.left;
                        it = i.top;
                        ir = i.right;
                        ib = i.bottom;
                    } else {
                        il = insets.getSystemWindowInsetLeft();
                        it = insets.getSystemWindowInsetTop();
                        ir = insets.getSystemWindowInsetRight();
                        ib = insets.getSystemWindowInsetBottom();
                        if (cutout && Build.VERSION.SDK_INT >= 28) {
                            DisplayCutout c = insets.getDisplayCutout();
                            if (c != null) {
                                il = Math.max(il, c.getSafeInsetLeft());
                                it = Math.max(it, c.getSafeInsetTop());
                                ir = Math.max(ir, c.getSafeInsetRight());
                                ib = Math.max(ib, c.getSafeInsetBottom());
                            }
                        }
                    }
                    view.setPadding(l + il, t + it, r + ir, b + ib);
                    return insets;
                }
            });
            v.requestApplyInsets();
        }

        static void refit(Window w) {
            w.getDecorView().requestApplyInsets();
        }
    }

    @TargetApi(19)
    private static final class Api19 {
        @SuppressWarnings("deprecation")
        static void systemBars(Window w, String mode) {
            boolean hideAll = "notch".equals(mode) || "full".equals(mode);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c == null) return;
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (hideAll) {
                    c.hide(WindowInsets.Type.systemBars());
                } else if ("status".equals(mode)) {
                    c.show(WindowInsets.Type.navigationBars());
                    c.hide(WindowInsets.Type.statusBars());
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            } else {
                // No LAYOUT_STABLE: fit() must see zero insets for the hidden bars, not the stable ones.
                w.getDecorView().setSystemUiVisibility(hideAll ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN : 0);
            }
        }
    }
}
