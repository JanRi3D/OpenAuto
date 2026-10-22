package me.ri3d.openauto.carplay.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import me.ri3d.openauto.diag.DeviceInfo;

/**
 * What Android on this device exposes for the transports CarPlay could use, read from the platform
 * at one moment. Passive: no USB device is opened and no Bluetooth connection is made here.
 * Observations and the app's own build settings are written in separate sections.
 */
final class TransportReport {
    /** RFCOMM service UUIDs from public iAP2 implementations; hypotheses until an iPhone answers. */
    static final String IPHONE_SERVICE = "00000000-deca-fade-deca-deafdecacafe", ACCESSORY_SERVICE = "00000000-deca-fade-deca-deafdecacaff";
    static final String PLACEHOLDER_ADDRESS = "02:00:00:00:00:00";
    static final String CONNECT = "android.permission.BLUETOOTH_CONNECT", SCAN = "android.permission.BLUETOOTH_SCAN";

    private TransportReport() {}

    static void device(List<String> out) {
        out.add("== this device, observed");
        out.add("Android " + Build.VERSION.RELEASE + ", runtime API level (SDK_INT) " + Build.VERSION.SDK_INT);
        out.add("fingerprint: " + Build.FINGERPRINT);
        out.add("manufacturer " + Build.MANUFACTURER + ", brand " + Build.BRAND + ", model " + Build.MODEL + ", device " + Build.DEVICE
                + ", product " + Build.PRODUCT + ", hardware " + Build.HARDWARE + ", board " + Build.BOARD);
        out.add("build " + Build.DISPLAY + " (id " + Build.ID + ", " + Build.TYPE + ", " + Build.TAGS + ")");
        out.add("ABIs: " + DeviceInfo.abis() + "; os.arch " + System.getProperty("os.arch") + "; kernel " + System.getProperty("os.version"));
    }

    static void app(Context ctx, List<String> out) {
        out.add("== this app, build settings (not observations)");
        ApplicationInfo ai = ctx.getApplicationInfo();
        String version;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            version = "unknown";
        }
        out.add(ctx.getPackageName() + " " + version + ", " + ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ? "debuggable" : "not debuggable")
                + ", targetSdk " + ai.targetSdkVersion + ", minSdk "
                + (Build.VERSION.SDK_INT >= 24 ? String.valueOf(ai.minSdkVersion) : "not readable below API 24 (see app/build.gradle.kts)"));
    }

    /** @return the paired devices Android lists, empty when it lists none or may not */
    static List<BluetoothDevice> bluetooth(Context ctx, List<String> out) {
        out.add("== Bluetooth, observed");
        List<BluetoothDevice> paired = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        out.add("system feature android.hardware.bluetooth: " + (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) ? "declared" : "not declared"));
        String[] needed = Build.VERSION.SDK_INT >= 31 ? new String[]{CONNECT, SCAN}
                : new String[]{"android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN"};
        for (String p : needed) {
            String kind = Build.VERSION.SDK_INT >= 31 ? "runtime" : "install-time";
            out.add("permission " + p.substring(p.lastIndexOf('.') + 1) + " (" + kind + "): "
                    + (granted(ctx, p) ? "granted" : declared(ctx, p) ? "not granted" : "not declared by the app"));
        }
        BluetoothAdapter a;
        try {
            a = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException e) {
            out.add("adapter: getDefaultAdapter() failed: " + e);
            a = null;
        }
        boolean permitted = Build.VERSION.SDK_INT < 31 || granted(ctx, CONNECT);
        if (a == null) {
            out.add("status: " + status(false, permitted, 0, null));
            return paired;
        }
        int state = a.getState();
        String address = null, shown;
        try {
            address = address(a);
            shown = "name " + a.getName() + ", address " + (problem(address) == null ? address : problem(address));
        } catch (SecurityException e) {
            shown = "name and address withheld (" + e.getMessage() + ")";
        }
        out.add("status: " + status(true, permitted, state, address));
        out.add("state " + stateName(state) + ", " + shown);
        if (!permitted) {
            out.add("paired devices: not listed, permission denied");
            return paired;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = a.getBondedDevices();
        } catch (SecurityException e) {
            out.add("paired devices: not listed, " + e.getMessage());
            return paired;
        }
        if (bonded == null || bonded.isEmpty()) {
            out.add("paired devices: " + (bonded == null ? "Android returned nothing" : "none listed")
                    + (state != BluetoothAdapter.STATE_ON ? " (Android lists none while Bluetooth is not on)" : ""));
            return paired;
        }
        out.add("paired devices: " + bonded.size());
        for (BluetoothDevice d : bonded) {
            paired.add(d);
            out.add("  " + label(d) + ", " + kind(d));
            out.add("    " + uuids(d));
        }
        return paired;
    }

    static String label(BluetoothDevice d) {
        try {
            return "\"" + d.getName() + "\" " + d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /**
     * Reads the adapter address to see whether Android hands it out at all: without
     * LOCAL_MAC_ADDRESS, which no ordinary app can hold, Android 6 and newer return a placeholder.
     */
    @SuppressLint({"MissingPermission", "HardwareIds"})
    private static String address(BluetoothAdapter a) {
        return a.getAddress();
    }

    private static String kind(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            String s = c == null ? "no device class" : "major class 0x" + Integer.toHexString(c.getMajorDeviceClass())
                    + (c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE ? " (phone)" : "");
            if (Build.VERSION.SDK_INT >= 18) {
                int t = d.getType();
                s += ", type " + (t == BluetoothDevice.DEVICE_TYPE_CLASSIC ? "classic" : t == BluetoothDevice.DEVICE_TYPE_LE ? "LE only"
                        : t == BluetoothDevice.DEVICE_TYPE_DUAL ? "dual" : "unknown");
            }
            return s;
        } catch (SecurityException e) {
            return "class and type withheld (" + e.getMessage() + ")";
        }
    }

    /** The service UUIDs Android cached from the device's last SDP answer; nothing is queried here. */
    private static String uuids(BluetoothDevice d) {
        ParcelUuid[] u;
        try {
            u = d.getUuids();
        } catch (SecurityException e) {
            return "cached service UUIDs: withheld (" + e.getMessage() + ")";
        }
        if (u == null) return "cached service UUIDs: none cached";
        boolean iap2 = false;
        List<String> all = new ArrayList<>();
        for (ParcelUuid p : u) {
            all.add(p.toString());
            iap2 |= IPHONE_SERVICE.equalsIgnoreCase(p.toString());
        }
        return "cached service UUIDs (" + u.length + "), iAP2 service " + IPHONE_SERVICE + " (hypothesis) " + (iap2 ? "listed" : "not listed")
                + ": " + all;
    }

    /** @return the USB devices Android lists */
    static List<UsbDevice> usb(Context ctx, List<String> out) {
        out.add("== USB, observed");
        List<UsbDevice> devices = new ArrayList<>();
        out.add("system feature android.hardware.usb.host: "
                + (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST) ? "declared" : "not declared"));
        UsbManager usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (usb == null) {
            out.add("devices: no USB service");
            return devices;
        }
        devices.addAll(usb.getDeviceList().values());
        out.add("devices: " + devices.size());
        for (UsbDevice d : devices) {
            out.add("  " + d.getDeviceName() + " " + id(d) + ", class " + hex2(d.getDeviceClass()) + "/" + hex2(d.getDeviceSubclass()) + "/"
                    + hex2(d.getDeviceProtocol()) + ", access " + (usb.hasPermission(d) ? "granted" : "not granted"));
            if (Build.VERSION.SDK_INT >= 21) {
                out.add("    manufacturer \"" + d.getManufacturerName() + "\", product \"" + d.getProductName() + "\", "
                        + d.getConfigurationCount() + " configuration(s)");
                for (int c = 0; c < d.getConfigurationCount(); c++) {
                    UsbConfiguration cfg = d.getConfiguration(c);
                    out.add("    configuration " + cfg.getId() + (cfg.getName() != null ? " \"" + cfg.getName() + "\"" : "") + ": max power "
                            + cfg.getMaxPower() + " mA" + (cfg.isSelfPowered() ? ", self-powered" : "") + (cfg.isRemoteWakeup() ? ", remote wakeup" : ""));
                    for (int i = 0; i < cfg.getInterfaceCount(); i++) iface(cfg.getInterface(i), out);
                }
            } else {
                out.add("    interfaces as Android lists them (configurations need API 21):");
                for (int i = 0; i < d.getInterfaceCount(); i++) iface(d.getInterface(i), out);
            }
        }
        return devices;
    }

    private static void iface(UsbInterface i, List<String> out) {
        out.add("      " + UsbDescriptors.iface(i.getId(), Build.VERSION.SDK_INT >= 21 ? i.getAlternateSetting() : -1, i.getInterfaceClass(),
                i.getInterfaceSubclass(), i.getInterfaceProtocol(), i.getEndpointCount()));
        for (int e = 0; e < i.getEndpointCount(); e++) {
            UsbEndpoint ep = i.getEndpoint(e);
            out.add("        " + UsbDescriptors.endpoint(ep.getAddress(), ep.getAttributes(), ep.getMaxPacketSize(), ep.getInterval()));
        }
    }

    static String id(UsbDevice d) {
        return hex4(d.getVendorId()) + ":" + hex4(d.getProductId());
    }

    /** The first reason that keeps this app from using Bluetooth, or that it can. */
    static String status(boolean adapter, boolean permitted, int state, String address) {
        if (!adapter) {
            return "no adapter exposed: Android gives this app no Bluetooth adapter. That does not prove the device has no Bluetooth"
                    + " hardware; a vendor stack can keep it to itself";
        }
        if (!permitted) return "permission denied: without BLUETOOTH_CONNECT Android withholds the name, address, paired devices and sockets";
        if (state != BluetoothAdapter.STATE_ON) return "Bluetooth disabled (" + stateName(state) + "): no paired devices or sockets until it is on";
        String p = problem(address);
        return p != null ? "on, adapter address unavailable: " + p : "on, adapter address available";
    }

    /** Why {@code address} is not this adapter's address, or null when it is one. */
    static String problem(String address) {
        if (address == null) return "none returned";
        if (PLACEHOLDER_ADDRESS.equals(address)) return "02:00:00:00:00:00 is the placeholder Android 6 and newer give apps without LOCAL_MAC_ADDRESS";
        if (!address.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) return "\"" + address + "\" is not a Bluetooth address";
        return null;
    }

    static String stateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF: return "off";
            case BluetoothAdapter.STATE_TURNING_ON: return "turning on";
            case BluetoothAdapter.STATE_ON: return "on";
            case BluetoothAdapter.STATE_TURNING_OFF: return "turning off";
            default: return "state " + state;
        }
    }

    private static boolean granted(Context ctx, String permission) {
        return ctx.checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean declared(Context ctx, String permission) {
        try {
            String[] requested = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            return requested != null && Arrays.asList(requested).contains(permission);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String hex2(int v) {
        String s = Integer.toHexString(v & 0xFF);
        return s.length() < 2 ? "0" + s : s;
    }

    private static String hex4(int v) {
        String s = Integer.toHexString(v & 0xFFFF);
        return "0000".substring(s.length()) + s;
    }
}
