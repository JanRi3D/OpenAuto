package me.ri3d.openauto;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Self Mode = the Android Auto phone app and this receiver on the same device, joined over
 * loopback TCP (the phone app's "Start head unit server" developer option listens on a TCP port).
 * It is only possible where the Android Auto app itself can run; it cannot run on Android 4.1.
 */
public final class SelfMode {
    public static final String GEARHEAD = "com.google.android.projection.gearhead";
    /** Android Auto app minimum platform version at the time of writing (Android 9). Re-check when it changes. */
    public static final int MIN_SDK_FOR_AA = 28;
    public static final int HEAD_UNIT_SERVER_PORT = 5277;

    private SelfMode() {}

    /** Null when the prerequisites that can be checked statically are met, otherwise the reason text. */
    public static String unavailableReason(Context ctx) {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_AA) {
            return ctx.getString(R.string.self_unavailable_api, Build.VERSION.RELEASE);
        }
        if (!installed(ctx)) {
            return ctx.getString(R.string.self_unavailable_app);
        }
        return null;
    }

    public static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(GEARHEAD, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static Intent openAndroidAuto(Context ctx) {
        return ctx.getPackageManager().getLaunchIntentForPackage(GEARHEAD);
    }
}
