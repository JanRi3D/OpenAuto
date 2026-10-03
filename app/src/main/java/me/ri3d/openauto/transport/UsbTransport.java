package me.ri3d.openauto.transport;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;

/**
 * Bulk transport over an accessory-mode phone (API 12 host API). Transfers are capped at 16 KiB,
 * the kernel limit before API 28, and always use offset 0 as the API 16 signature requires.
 * Reads use a 1 s timeout so {@link #close()} and detach are noticed without busy polling.
 */
public final class UsbTransport implements Transport {
    private static final int MAX_TRANSFER = 16384;
    private static final int READ_TIMEOUT_MS = 1000, WRITE_TIMEOUT_MS = 2000;

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint in, out;
    private final byte[] scratch = new byte[MAX_TRANSFER];
    private final String label;
    private volatile boolean closed;

    private UsbTransport(UsbDeviceConnection conn, UsbInterface iface, UsbEndpoint in, UsbEndpoint out, String label) {
        this.conn = conn;
        this.iface = iface;
        this.in = in;
        this.out = out;
        this.label = label;
    }

    /** Opens an accessory-mode device: interface 0, the first bulk IN and bulk OUT endpoints (§10.4). */
    public static UsbTransport open(UsbManager um, UsbDevice dev) throws IOException {
        if (!Aoa.isAccessory(dev)) throw new IOException("device is not in accessory mode");
        if (dev.getInterfaceCount() < 1) throw new IOException("accessory has no interfaces");
        UsbInterface iface = dev.getInterface(0);
        UsbEndpoint epIn = null, epOut = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) { if (epIn == null) epIn = ep; }
            else if (epOut == null) epOut = ep;
        }
        if (epIn == null || epOut == null) throw new IOException("accessory interface lacks bulk IN/OUT endpoints");
        UsbDeviceConnection c = um.openDevice(dev);
        if (c == null) throw new IOException("openDevice failed (permission?)");
        if (!c.claimInterface(iface, true)) {
            c.close();
            throw new IOException("claimInterface failed");
        }
        return new UsbTransport(c, iface, epIn, epOut, "USB " + dev.getDeviceName());
    }

    @Override
    public int read(byte[] buf, int maxLen) throws IOException {
        int len = Math.min(maxLen, MAX_TRANSFER);
        int fastFailures = 0;
        while (!closed) {
            long t0 = System.currentTimeMillis();
            int n = conn.bulkTransfer(in, buf, len, READ_TIMEOUT_MS);
            if (n > 0) return n;
            if (n == 0) continue; // zero-length packet
            // API 16 returns -1 for both timeout and error; an error returns at once, a timeout after READ_TIMEOUT_MS.
            if (System.currentTimeMillis() - t0 < READ_TIMEOUT_MS / 2) {
                if (++fastFailures >= 5) throw new IOException("USB read failed (device detached?)");
                try { Thread.sleep(20); } catch (InterruptedException e) { throw new IOException("interrupted"); }
            } else {
                fastFailures = 0;
            }
        }
        return -1;
    }

    @Override
    public void write(byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int chunk = Math.min(MAX_TRANSFER, len - off);
            byte[] src = buf;
            if (off != 0) { // bulkTransfer(API<18) has no offset parameter
                System.arraycopy(buf, off, scratch, 0, chunk);
                src = scratch;
            }
            int n = conn.bulkTransfer(out, src, chunk, WRITE_TIMEOUT_MS);
            if (n < 0) throw new IOException("USB write failed (" + n + ")");
            off += n;
        }
    }

    @Override
    public String describe() {
        return label;
    }

    @Override
    public void close() {
        closed = true;
        try { conn.releaseInterface(iface); } catch (RuntimeException ignored) { }
        try { conn.close(); } catch (RuntimeException ignored) { }
    }
}
