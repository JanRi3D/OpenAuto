package me.ri3d.openauto.diag;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Debug;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import me.ri3d.openauto.R;

/**
 * Facts about the device for the Diagnostics screen and for capability gating.
 * Every value is read from the platform; nothing is estimated. Where an API is missing on this
 * Android version the value is reported as unknown.
 */
public final class DeviceInfo {
    private DeviceInfo() {}

    public static boolean usbHost(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
    }

    public static boolean hasBluetooth() {
        try {
            return BluetoothAdapter.getDefaultAdapter() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean hasMic(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
    }

    /** True when the hidden WifiManager.setWifiApEnabled method exists (reflection; pre-Oreo devices). */
    public static boolean wifiApApi(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            wm.getClass().getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long totalRam(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem > 0) return mi.totalMem;
        return procMeminfo("MemTotal:");
    }

    private static long procMeminfo(String key) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) {
                    String[] p = line.trim().split("\\s+");
                    return Long.parseLong(p[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static String mb(long bytes) {
        if (bytes < 0) return null;
        return String.format(Locale.US, "%.0f MiB", bytes / 1048576.0);
    }

    /** Label/value pairs for the Diagnostics screen. Decoder probes are appended by the caller. */
    public static List<String[]> collect(Context ctx) {
        List<String[]> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        out.add(row(ctx, R.string.diag_android, Build.VERSION.RELEASE));
        out.add(row(ctx, R.string.diag_api, String.valueOf(Build.VERSION.SDK_INT)));
        out.add(row(ctx, R.string.diag_device, Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ", " + Build.HARDWARE + ")"));
        out.add(row(ctx, R.string.diag_abi, abis()));

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        d.getMetrics(m);
        String size = m.widthPixels + " x " + m.heightPixels + " px";
        if (Build.VERSION.SDK_INT >= 17) {
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            if (p.x != m.widthPixels || p.y != m.heightPixels) size += " (panel " + p.x + " x " + p.y + ")";
        }
        out.add(row(ctx, R.string.diag_screen, size + ", " + Math.round(m.widthPixels / m.density) + " x " + Math.round(m.heightPixels / m.density) + " dp"));
        out.add(row(ctx, R.string.diag_density, m.densityDpi + " dpi (x " + m.density + "), physical " + Math.round(m.xdpi) + " x " + Math.round(m.ydpi)));

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        out.add(row(ctx, R.string.diag_mem_total, mb(totalRam(ctx))));
        out.add(row(ctx, R.string.diag_mem_avail, mb(mi.availMem) + ", threshold " + mb(mi.threshold)));
        out.add(row(ctx, R.string.diag_mem_low, ctx.getString(mi.lowMemory ? R.string.yes : R.string.no)));
        out.add(row(ctx, R.string.diag_heap, am.getMemoryClass() + " MiB"));
        out.add(row(ctx, R.string.diag_heap_large, am.getLargeMemoryClass() + " MiB"));
        Runtime rt = Runtime.getRuntime();
        out.add(row(ctx, R.string.diag_heap_used, mb(rt.totalMemory() - rt.freeMemory()) + " of " + mb(rt.totalMemory()) + ", max " + mb(rt.maxMemory())));
        out.add(row(ctx, R.string.diag_native, mb(Debug.getNativeHeapAllocatedSize()) + " of " + mb(Debug.getNativeHeapSize())));
        Debug.MemoryInfo dm = new Debug.MemoryInfo();
        Debug.getMemoryInfo(dm);
        out.add(row(ctx, R.string.diag_pss, String.format(Locale.US, "%d KiB (dalvik %d, native %d, other %d)",
                dm.getTotalPss(), dm.dalvikPss, dm.nativePss, dm.otherPss)));

        boolean host = usbHost(ctx);
        String usb = ctx.getString(host ? R.string.yes : R.string.no);
        if (pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)) usb += ", accessory mode too";
        out.add(row(ctx, R.string.diag_usb_host, usb));
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            out.add(row(ctx, R.string.diag_usb_devices, um == null ? null : String.valueOf(um.getDeviceList().size())));
            if (um != null) {
                for (android.hardware.usb.UsbDevice dev : um.getDeviceList().values()) {
                    out.add(row(ctx, R.string.diag_usb_list, me.ri3d.openauto.transport.UsbKinds.describe(dev)
                            + (me.ri3d.openauto.transport.UsbKinds.looksLikePhone(dev) ? ", phone" : ", not a phone")));
                }
            }
        } catch (RuntimeException e) {
            out.add(row(ctx, R.string.diag_usb_devices, null));
        }

        String wifi;
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            wifi = ctx.getString(R.string.no);
        } else {
            try {
                WifiManager w = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifi = w != null && w.isWifiEnabled() ? "On" : "Off";
                if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) wifi += ", Wi-Fi Direct";
                if (Build.VERSION.SDK_INT >= 21 && w != null) {
                    out.add(row(ctx, R.string.diag_wifi_5g, ctx.getString(w.is5GHzBandSupported() ? R.string.yes : R.string.no)));
                }
            } catch (RuntimeException e) {
                wifi = ctx.getString(R.string.unknown);
            }
        }
        out.add(row(ctx, R.string.diag_wifi, wifi));
        out.add(row(ctx, R.string.diag_wifi_ap, ctx.getString(wifiApApi(ctx) ? R.string.yes : R.string.no)));

        String bt;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                bt = ctx.getString(R.string.bt_none);
            } else {
                bt = a.isEnabled() ? "On" : "Off";
                try {
                    String name = a.getName();
                    if (name != null) bt += ", " + name;
                } catch (SecurityException ignored) {
                }
                if (pm.hasSystemFeature("android.hardware.bluetooth_le")) bt += ", LE";
            }
        } catch (RuntimeException e) {
            bt = ctx.getString(R.string.unknown);
        }
        out.add(row(ctx, R.string.diag_bt, bt));

        String mic;
        if (!hasMic(ctx)) {
            mic = ctx.getString(R.string.no);
        } else {
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mic = min > 0 ? "Yes, 16 kHz mono min buffer " + min + " B" : "Reported, but 16 kHz mono not supported";
        }
        out.add(row(ctx, R.string.diag_mic, mic));

        List<String> dec = Decoders.avcDecoders();
        if (dec.isEmpty()) {
            out.add(row(ctx, R.string.diag_decoders, ctx.getString(R.string.diag_decoder_none)));
        } else {
            StringBuilder sb = new StringBuilder();
            for (String n : dec) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(n);
                String lvl = Decoders.maxLevel(n);
                if (lvl != null) sb.append(" (level ").append(lvl).append(')');
                if (Decoders.isSoftware(n)) sb.append(" [software]");
            }
            out.add(row(ctx, R.string.diag_decoders, sb.toString()));
        }
        out.add(row(ctx, R.string.diag_ip, ips()));
        return out;
    }

    private static String[] row(Context ctx, int label, String value) {
        return new String[]{ctx.getString(label), value == null ? ctx.getString(R.string.unknown) : value};
    }

    @SuppressWarnings("deprecation")
    public static String abis() {
        if (Build.VERSION.SDK_INT >= 21) {
            StringBuilder sb = new StringBuilder();
            for (String a : Build.SUPPORTED_ABIS) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a);
            }
            return sb.toString();
        }
        String s = Build.CPU_ABI;
        if (Build.CPU_ABI2 != null && !Build.CPU_ABI2.isEmpty() && !"unknown".equals(Build.CPU_ABI2)) s += ", " + Build.CPU_ABI2;
        return s;
    }

    public static String ips() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            for (NetworkInterface ni : Collections.list(ifs)) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.getHostAddress().contains(":")) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(ni.getName()).append(' ').append(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
