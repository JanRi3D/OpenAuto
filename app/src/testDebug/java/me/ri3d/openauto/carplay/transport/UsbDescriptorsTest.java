package me.ri3d.openauto.carplay.transport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Raw USB descriptors as Android returns them, read without touching any device. */
public class UsbDescriptorsTest {
    private static final byte[] DEVICE = bytes(0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x40, 0xAC, 0x05, 0xA8, 0x12, 0x01, 0x10, 0x01, 0x02, 0x03, 0x04);

    @Test
    public void oneConfigurationWithItsInterfaceAndEndpoints() {
        byte[] raw = concat(DEVICE,
                bytes(0x09, 0x02, 0x2E, 0x00, 0x01, 0x01, 0x00, 0xC0, 0xFA),
                bytes(0x09, 0x04, 0x00, 0x00, 0x03, 0x06, 0x01, 0x01, 0x00),
                bytes(0x07, 0x05, 0x81, 0x02, 0x00, 0x02, 0x00),
                bytes(0x07, 0x05, 0x02, 0x02, 0x00, 0x02, 0x00),
                bytes(0x07, 0x05, 0x83, 0x03, 0x40, 0x00, 0x08),
                bytes(0x05, 0x24, 0x00, 0x10, 0x01),
                bytes(0x08, 0x0B, 0x00, 0x02, 0x02, 0x0D, 0x00, 0x00));
        assertEquals(Arrays.asList(
                "device: USB 2.00, class 00/00/00 (defined per interface), control max packet 64, id 05ac:12a8, release 10.01, string indexes 1/2/3, 4 configuration(s)",
                "configuration 1: 1 interface(s), total length 46, attributes 0xc0 self-powered, max power 500 mA, string index 0",
                "  interface 0 alt 0: class 06/01/01 (still image), 3 endpoint(s), string index 0",
                "    endpoint 0x81 in, bulk, max packet 512, interval 0",
                "    endpoint 0x02 out, bulk, max packet 512, interval 0",
                "    endpoint 0x83 in, interrupt, max packet 64, interval 8",
                "    descriptor type 0x24, 5 bytes: 05 24 00 10 01",
                "  interface association: interfaces 0 to 1, class 02/0d/00 (communications)"), UsbDescriptors.describe(raw));
    }

    @Test
    public void zeroLengthStopsInsteadOfLooping() {
        List<String> r = UsbDescriptors.describe(concat(DEVICE, bytes(0, 0, 0)));
        assertEquals(2, r.size());
        assertEquals("offset 18: length 0 cannot be a descriptor; 3 byte(s) not parsed: 00 00 00", r.get(1));
    }

    @Test
    public void descriptorRunningPastTheEndIsShownAsBytes() {
        assertEquals(Arrays.asList("offset 0: a 18-byte descriptor, but only 10 byte(s) left: 12 01 00 02 00 00 00 40 AC 05"),
                UsbDescriptors.describe(Arrays.copyOf(DEVICE, 10)));
    }

    @Test
    public void descriptorShorterThanItsTypeIsNotReadPastItsLength() {
        assertEquals(Arrays.asList("descriptor type 4 of 5 bytes, shorter than the 9 it needs: 05 04 00 00 03",
                        "    endpoint 0x81 in, bulk, max packet 512, interval 0"),
                UsbDescriptors.describe(bytes(0x05, 0x04, 0x00, 0x00, 0x03, 0x07, 0x05, 0x81, 0x02, 0x00, 0x02, 0x00)));
    }

    @Test
    public void platformValuesUseTheSameFormat() {
        assertEquals("endpoint 0x81 in, isochronous, max packet 1024 x3, interval 1", UsbDescriptors.endpoint(0x81, 1, 0x1400, 1));
        assertEquals("interface 2: class ff/fe/02 (vendor-specific), 2 endpoint(s)", UsbDescriptors.iface(2, -1, 0xFF, 0xFE, 0x02, 2));
        assertEquals("", UsbDescriptors.hex(new byte[0], 0, 0));
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
