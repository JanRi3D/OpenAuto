package me.ri3d.openauto;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Runtime-permission guards. On API < 23 every manifest permission is granted at install time. */
public final class Perms {
    public static final int REQ_BT = 1, REQ_MIC = 2, REQ_HOTSPOT = 3;

    private Perms() {}

    public static boolean has(Context ctx, String permission) {
        return Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Bluetooth connect/advertise are runtime permissions from API 31; before that BLUETOOTH suffices. */
    public static boolean hasBluetooth(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return has(ctx, "android.permission.BLUETOOTH_CONNECT") && has(ctx, "android.permission.BLUETOOTH_ADVERTISE");
    }

    public static void requestBluetooth(Activity a) {
        if (Build.VERSION.SDK_INT >= 31) {
            a.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"}, REQ_BT);
        }
    }

    /** Location is what startLocalOnlyHotspot demands on API 26-32; NEARBY_WIFI_DEVICES from 33. */
    public static boolean hasHotspot(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return true;
        if (Build.VERSION.SDK_INT >= 33) return has(ctx, "android.permission.NEARBY_WIFI_DEVICES");
        return has(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public static void requestHotspot(Activity a) {
        if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(new String[]{"android.permission.NEARBY_WIFI_DEVICES"}, REQ_HOTSPOT);
        else if (Build.VERSION.SDK_INT >= 26) a.requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_HOTSPOT);
    }

    public static boolean hasMic(Context ctx) {
        return has(ctx, android.Manifest.permission.RECORD_AUDIO);
    }

    public static void requestMic(Activity a) {
        if (Build.VERSION.SDK_INT >= 23) a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }
}
