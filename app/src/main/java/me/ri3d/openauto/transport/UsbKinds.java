package me.ri3d.openauto.transport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;

/**
 * Decides from USB descriptors alone whether a device is worth treating as a phone. Head units have
 * internal USB devices (a real one exposed a "DAB USB Dongle", 16c0:05dc, vendor-specific class), and
 * sending Android Open Accessory vendor requests to those is neither useful nor safe, so only devices
 * that show an Android-style interface are offered for a wired connection.
 */
public final class UsbKinds {
    private UsbKinds() {}

    /**
     * @param interfaces one {class, subclass, protocol} triple per interface
     * @return true for a phone already in accessory mode, or one exposing MTP, PTP or ADB
     */
    public static boolean looksLikePhone(int vendorId, int productId, int deviceClass, int[][] interfaces) {
        if (vendorId == Aoa.GOOGLE_VID && (productId == Aoa.PID_ACCESSORY || productId == Aoa.PID_ACCESSORY_ADB)) return true;
        if (deviceClass == 9) return false; // hub
        for (int[] i : interfaces) {
            int cls = i[0], sub = i[1], proto = i[2];
            if (cls == 6 && sub == 1 && proto == 1) return true;        // PTP (still image)
            if (cls == 0xFF && sub == 0xFF && proto == 0) return true;  // MTP as Android exposes it
            if (cls == 0xFF && sub == 0x42) return true;                // ADB (protocol 1) or fastboot (3)
            if (cls == 0xE0 && sub == 1 && proto == 3) return true;     // RNDIS: phone with USB tethering on
        }
        // Known limitation of this allow-list: a phone that shows only MIDI or NCM tethering is missed.
        // Switch it to file transfer, or add its interface triple here.
        return false;
    }

    public static boolean looksLikePhone(UsbDevice d) {
        return looksLikePhone(d.getVendorId(), d.getProductId(), d.getDeviceClass(), interfaces(d));
    }

    public static String describe(UsbDevice d) {
        return describe(d.getVendorId(), d.getProductId(), d.getDeviceClass(), interfaces(d));
    }

    private static int[][] interfaces(UsbDevice d) {
        int[][] out = new int[d.getInterfaceCount()][];
        for (int n = 0; n < out.length; n++) {
            UsbInterface i = d.getInterface(n);
            out[n] = new int[]{i.getInterfaceClass(), i.getInterfaceSubclass(), i.getInterfaceProtocol()};
        }
        return out;
    }

    /** "16c0:05dc class ff [ff/ff/ff]" for logs and Diagnostics. */
    public static String describe(int vendorId, int productId, int deviceClass, int[][] interfaces) {
        StringBuilder sb = new StringBuilder();
        sb.append(hex4(vendorId)).append(':').append(hex4(productId)).append(" class ").append(Integer.toHexString(deviceClass)).append(" [");
        for (int n = 0; n < interfaces.length; n++) {
            if (n > 0) sb.append(' ');
            sb.append(Integer.toHexString(interfaces[n][0])).append('/').append(Integer.toHexString(interfaces[n][1]))
                    .append('/').append(Integer.toHexString(interfaces[n][2]));
        }
        return sb.append(']').toString();
    }

    private static String hex4(int v) {
        String s = Integer.toHexString(v & 0xFFFF);
        return "0000".substring(s.length()) + s;
    }
}
