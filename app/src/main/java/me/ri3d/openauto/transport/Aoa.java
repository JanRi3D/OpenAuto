package me.ri3d.openauto.transport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import java.io.IOException;

/**
 * Android Open Accessory negotiation, performed by the head unit as USB host
 * (protocol-reference §10). After {@link #start} the phone drops off the bus and re-enumerates as
 * {@code 18D1:2D00} (or {@code 2D01} with ADB); the caller waits for that device.
 */
public final class Aoa {
    public static final int GOOGLE_VID = 0x18D1;
    public static final int PID_ACCESSORY = 0x2D00, PID_ACCESSORY_ADB = 0x2D01;

    private static final int REQ_GET_PROTOCOL = 51, REQ_SEND_STRING = 52, REQ_START = 53;
    private static final int TYPE_VENDOR_IN = 0xC0, TYPE_VENDOR_OUT = 0x40;
    private static final int TIMEOUT_MS = 1000;

    // Strings the phone's Android Auto app matches on (same values aasdk sends; §10.1).
    static final String[] STRINGS = {"Android", "Android Auto", "Android Auto", "2.0.1", "https://f1xstudio.com", "HU-AAAAAA001"};

    private Aoa() {}

    public static boolean isAccessory(UsbDevice d) {
        return d.getVendorId() == GOOGLE_VID && (d.getProductId() == PID_ACCESSORY || d.getProductId() == PID_ACCESSORY_ADB);
    }

    /** Returns the AOA protocol version (1 or 2) or throws if the device does not support AOA. */
    public static int protocolVersion(UsbDeviceConnection c) throws IOException {
        byte[] buf = new byte[2];
        int n = c.controlTransfer(TYPE_VENDOR_IN, REQ_GET_PROTOCOL, 0, 0, buf, 2, TIMEOUT_MS);
        if (n < 2) throw new IOException("GET_PROTOCOL failed (" + n + "): not an AOA-capable device");
        int v = (buf[0] & 0xFF) | ((buf[1] & 0xFF) << 8);
        if (v != 1 && v != 2) throw new IOException("unsupported AOA protocol version " + v);
        return v;
    }

    /** Sends the identification strings and switches the phone to accessory mode. */
    public static void start(UsbDeviceConnection c) throws IOException {
        for (int i = 0; i < STRINGS.length; i++) {
            byte[] s = (STRINGS[i] + '\0').getBytes("US-ASCII");
            int n = c.controlTransfer(TYPE_VENDOR_OUT, REQ_SEND_STRING, 0, i, s, s.length, TIMEOUT_MS);
            if (n < 0) throw new IOException("SEND_STRING " + i + " failed (" + n + ")");
        }
        int n = c.controlTransfer(TYPE_VENDOR_OUT, REQ_START, 0, 0, null, 0, TIMEOUT_MS);
        if (n < 0) throw new IOException("ACCESSORY_START failed (" + n + ")");
    }
}
