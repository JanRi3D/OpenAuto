package me.ri3d.openauto.carplay.transport;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw descriptors Android returns for an opened USB device ({@code getRawDescriptors},
 * the device descriptor followed by every configuration the kernel read) into one line per
 * descriptor. Read-only: nothing here talks to the device. A descriptor that is shorter than its
 * type needs, or runs past the end, is shown as bytes and parsing stops where the data stops
 * making sense; the platform's own interface list is formatted with the same helpers.
 */
final class UsbDescriptors {
    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();
    static final int DEVICE = 1, CONFIGURATION = 2, INTERFACE = 4, ENDPOINT = 5, INTERFACE_ASSOCIATION = 11;

    private UsbDescriptors() {}

    static List<String> describe(byte[] raw) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            if (len < 2) {
                out.add("offset " + i + ": length " + len + " cannot be a descriptor; " + (raw.length - i) + " byte(s) not parsed: " + hex(raw, i, raw.length - i));
                break;
            }
            if (i + len > raw.length) {
                out.add("offset " + i + ": a " + len + "-byte descriptor, but only " + (raw.length - i) + " byte(s) left: " + hex(raw, i, raw.length - i));
                break;
            }
            out.add(line(raw, i, len));
            i += len;
        }
        return out;
    }

    private static String line(byte[] b, int at, int len) {
        int type = b[at + 1] & 0xFF;
        int need = type == DEVICE ? 18 : type == CONFIGURATION || type == INTERFACE ? 9 : type == ENDPOINT ? 7 : type == INTERFACE_ASSOCIATION ? 8 : 2;
        if (len < need) return "descriptor type " + type + " of " + len + " bytes, shorter than the " + need + " it needs: " + hex(b, at, len);
        switch (type) {
            case DEVICE:
                return "device: USB " + bcd(u16(b, at + 2)) + ", class " + triple(b[at + 4], b[at + 5], b[at + 6])
                        + ", control max packet " + (b[at + 7] & 0xFF) + ", id " + hex4(u16(b, at + 8)) + ":" + hex4(u16(b, at + 10))
                        + ", release " + bcd(u16(b, at + 12)) + ", string indexes " + (b[at + 14] & 0xFF) + "/" + (b[at + 15] & 0xFF) + "/" + (b[at + 16] & 0xFF)
                        + ", " + (b[at + 17] & 0xFF) + " configuration(s)";
            case CONFIGURATION: {
                int attributes = b[at + 7] & 0xFF;
                return "configuration " + (b[at + 5] & 0xFF) + ": " + (b[at + 4] & 0xFF) + " interface(s), total length " + u16(b, at + 2)
                        + ", attributes 0x" + Integer.toHexString(attributes)
                        + ((attributes & 0x40) != 0 ? " self-powered" : "") + ((attributes & 0x20) != 0 ? " remote-wakeup" : "")
                        + ", max power " + 2 * (b[at + 8] & 0xFF) + " mA, string index " + (b[at + 6] & 0xFF);
            }
            case INTERFACE:
                return "  " + iface(b[at + 2] & 0xFF, b[at + 3] & 0xFF, b[at + 5] & 0xFF, b[at + 6] & 0xFF, b[at + 7] & 0xFF, b[at + 4] & 0xFF)
                        + ", string index " + (b[at + 8] & 0xFF);
            case ENDPOINT:
                return "    " + endpoint(b[at + 2] & 0xFF, b[at + 3] & 0xFF, u16(b, at + 4), b[at + 6] & 0xFF);
            case INTERFACE_ASSOCIATION:
                return "  interface association: interfaces " + (b[at + 2] & 0xFF) + " to " + ((b[at + 2] & 0xFF) + (b[at + 3] & 0xFF) - 1)
                        + ", class " + triple(b[at + 4], b[at + 5], b[at + 6]);
            default:
                return "    descriptor type 0x" + Integer.toHexString(type) + ", " + len + " bytes: " + hex(b, at, len);
        }
    }

    /** "interface 0 alt 0: class 06/01/01 (still image), 3 endpoint(s)"; {@code alt} below 0 when unknown. */
    static String iface(int number, int alt, int cls, int subclass, int protocol, int endpoints) {
        return "interface " + number + (alt >= 0 ? " alt " + alt : "") + ": class " + triple((byte) cls, (byte) subclass, (byte) protocol)
                + ", " + endpoints + " endpoint(s)";
    }

    /** "endpoint 0x81 in, bulk, max packet 512, interval 0". */
    static String endpoint(int address, int attributes, int maxPacket, int interval) {
        String[] types = {"control", "isochronous", "bulk", "interrupt"};
        int extra = (maxPacket >> 11) & 3;
        return "endpoint 0x" + hex2(address) + ((address & 0x80) != 0 ? " in" : " out") + ", " + types[attributes & 3]
                + ", max packet " + (maxPacket & 0x7FF) + (extra != 0 ? " x" + (extra + 1) : "") + ", interval " + interval;
    }

    private static String triple(byte cls, byte subclass, byte protocol) {
        int c = cls & 0xFF;
        String name;
        switch (c) {
            case 0x00: name = "defined per interface"; break;
            case 0x01: name = "audio"; break;
            case 0x02: name = "communications"; break;
            case 0x03: name = "HID"; break;
            case 0x06: name = "still image"; break;
            case 0x08: name = "mass storage"; break;
            case 0x09: name = "hub"; break;
            case 0x0A: name = "CDC data"; break;
            case 0xE0: name = "wireless"; break;
            case 0xEF: name = "miscellaneous"; break;
            case 0xFF: name = "vendor-specific"; break;
            default: name = null;
        }
        return hex2(c) + "/" + hex2(subclass & 0xFF) + "/" + hex2(protocol & 0xFF) + (name != null ? " (" + name + ")" : "");
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static String bcd(int v) {
        return Integer.toHexString(v >> 8) + "." + hex2(v & 0xFF);
    }

    private static String hex2(int v) {
        return v < 0x10 ? "0" + Integer.toHexString(v) : Integer.toHexString(v);
    }

    private static String hex4(int v) {
        return hex2(v >> 8) + hex2(v & 0xFF);
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            if (i > off) sb.append(' ');
            sb.append(DIGITS[(b[i] >> 4) & 15]).append(DIGITS[b[i] & 15]);
        }
        return sb.toString();
    }
}
