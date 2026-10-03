package me.ri3d.openauto.wireless;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

import me.ri3d.openauto.diag.DeviceInfo;

/**
 * Starts the head unit's Wi-Fi access point that the phone joins for projection.
 *
 * API 16–25: the hidden {@code WifiManager.setWifiApEnabled} via reflection (no public API exists;
 * works on most pre-Oreo devices, and Diagnostics shows whether the method is present).
 * API 26+: {@code startLocalOnlyHotspot}, which picks its own SSID/passphrase (we pass them to the
 * phone over Bluetooth, so that is fine) and needs the location permission.
 */
public final class Hotspot {
    private static final String TAG = "Hotspot";

    public interface Callback {
        void onStarted(String ssid, String passphrase, String bssid, String ip);
        void onFailed(String reason);
    }

    private final Context ctx;
    private final WifiManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object reservation; // WifiManager.LocalOnlyHotspotReservation on API 26+
    private boolean legacyStarted;

    public Hotspot(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        wm = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
    }

    public static boolean supported(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) return true;
        return DeviceInfo.wifiApApi(ctx);
    }

    public void start(final String ssid, final String passphrase, final Callback cb) {
        if (wm == null) {
            cb.onFailed("no Wi-Fi service");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) startLocalOnly(cb);
        else startLegacy(ssid, passphrase, cb);
    }

    // ---- API 26+ -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller checks location permission (Perms.hasLocation)
    private void startLocalOnly(final Callback cb) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                @SuppressWarnings("deprecation")
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    String ssid, pass;
                    if (Build.VERSION.SDK_INT >= 30) {
                        SoftApConfiguration c = r.getSoftApConfiguration();
                        ssid = c.getSsid();
                        pass = c.getPassphrase();
                    } else {
                        WifiConfiguration c = r.getWifiConfiguration();
                        ssid = c == null ? null : c.SSID;
                        pass = c == null ? null : c.preSharedKey;
                    }
                    String ip = apAddress();
                    Log.i(TAG, "local-only hotspot up: " + ssid + " at " + ip);
                    cb.onStarted(ssid, pass, macOf(ip), ip);
                }

                @Override
                public void onFailed(int reason) {
                    cb.onFailed("hotspot failed (reason " + reason + ")");
                }

                @Override
                public void onStopped() {
                    reservation = null;
                }
            }, main);
        } catch (SecurityException | IllegalStateException e) {
            cb.onFailed("hotspot: " + e.getMessage());
        }
    }

    // ---- API 16–25 -----------------------------------------------------------------------------

    private void startLegacy(final String ssid, final String passphrase, final Callback cb) {
        new Thread(() -> {
            try {
                WifiConfiguration c = new WifiConfiguration();
                c.SSID = ssid;
                c.preSharedKey = passphrase;
                c.allowedKeyManagement.clear();
                c.allowedKeyManagement.set(4); // WifiConfiguration.KeyMgmt.WPA2_PSK (hidden constant)
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                if (wm.isWifiEnabled()) wm.setWifiEnabled(false); // AP and station are exclusive here
                Method m = wm.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                Object ok = m.invoke(wm, c, true);
                if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("setWifiApEnabled returned " + ok);
                Method state = wm.getClass().getMethod("getWifiApState");
                long deadline = System.currentTimeMillis() + 10000;
                int st = -1;
                while (System.currentTimeMillis() < deadline) {
                    st = (Integer) state.invoke(wm);
                    if (st == 13 || st == 3) break; // WIFI_AP_STATE_ENABLED is 13 on 4.x (3 on very old builds)
                    Thread.sleep(250);
                }
                if (st != 13 && st != 3) throw new IllegalStateException("hotspot did not come up (state " + st + ")");
                legacyStarted = true;
                String ip = null;
                for (int i = 0; i < 20 && ip == null; i++) {
                    ip = apAddress();
                    if (ip == null) Thread.sleep(250);
                }
                final String ipFinal = ip == null ? "192.168.43.1" : ip; // Android's default tethering address
                Log.i(TAG, "legacy hotspot up: " + ssid + " at " + ipFinal + (ip == null ? " (assumed)" : ""));
                main.post(() -> cb.onStarted(ssid, passphrase, macOf(ipFinal), ipFinal));
            } catch (final Exception e) {
                Log.w(TAG, "legacy hotspot failed", e);
                main.post(() -> cb.onFailed("hotspot: " + e));
            }
        }, "hotspot").start();
    }

    public void stop() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (reservation != null) {
                try {
                    ((WifiManager.LocalOnlyHotspotReservation) reservation).close();
                } catch (RuntimeException ignored) {
                }
                reservation = null;
            }
            return;
        }
        if (legacyStarted && wm != null) {
            legacyStarted = false;
            try {
                Method m = wm.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                m.invoke(wm, null, false);
            } catch (Exception e) {
                Log.w(TAG, "disable hotspot: " + e);
            }
        }
    }

    /** IPv4 address of the AP interface: a private address on an interface named wlan, ap, swlan or softap. */
    static String apAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.getHostAddress().contains(":")) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** MAC of the interface holding {@code ip}, as "aa:bb:cc:dd:ee:ff"; empty when unknown (API 23+ hides it). */
    static String macOf(String ip) {
        if (ip == null) return "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (ip.equals(a.getHostAddress())) {
                        byte[] mac = ni.getHardwareAddress();
                        if (mac == null) return "";
                        StringBuilder sb = new StringBuilder();
                        for (byte b : mac) sb.append(sb.length() == 0 ? "" : ":").append(String.format(Locale.US, "%02x", b & 0xFF));
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
