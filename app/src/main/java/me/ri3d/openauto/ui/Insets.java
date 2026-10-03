package me.ri3d.openauto.ui;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Window-inset handling for modern Android, where apps are drawn edge to edge: without it the
 * layout runs under the navigation bar and the camera cutout. Everything here is a no-op on the
 * Android 4.1 head unit; newer API calls live in nested classes so old runtimes never load them.
 */
public final class Insets {
    private Insets() {}

    /** Adds the system-bar and display-cutout insets to the view's own padding. */
    public static void fit(View v) {
        if (Build.VERSION.SDK_INT >= 21) Api21.fit(v);
    }

    /** Hides status and navigation bars (swipe shows them briefly) and lets the window use the cutout edge. */
    public static void immersive(Window w) {
        if (Build.VERSION.SDK_INT >= 19) Api19.immersive(w);
    }

    @TargetApi(21)
    private static final class Api21 {
        static void fit(View v) {
            final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                @SuppressWarnings("deprecation")
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    int il, it, ir, ib;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                        il = i.left;
                        it = i.top;
                        ir = i.right;
                        ib = i.bottom;
                    } else {
                        il = insets.getSystemWindowInsetLeft();
                        it = insets.getSystemWindowInsetTop();
                        ir = insets.getSystemWindowInsetRight();
                        ib = insets.getSystemWindowInsetBottom();
                        if (Build.VERSION.SDK_INT >= 28) {
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
    }

    @TargetApi(19)
    private static final class Api19 {
        @SuppressWarnings("deprecation")
        static void immersive(Window w) {
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        }
    }
}
