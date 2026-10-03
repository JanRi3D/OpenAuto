package me.ri3d.openauto.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbKindsTest {

    @Test
    public void theRadiosDabDongleIsNotAPhone() {
        // From the real head unit's sysfs: 16c0:05dc, bDeviceClass ff, one interface ff/ff/ff ("DAB USB Dongle").
        int[][] dab = {{0xFF, 0xFF, 0xFF}};
        assertFalse(UsbKinds.looksLikePhone(0x16C0, 0x05DC, 0xFF, dab));
        assertEquals("16c0:05dc class ff [ff/ff/ff]", UsbKinds.describe(0x16C0, 0x05DC, 0xFF, dab));
    }

    @Test
    public void androidInterfacesAndAccessoryModeArePhones() {
        assertTrue("MTP", UsbKinds.looksLikePhone(0x04E8, 0x6860, 0, new int[][]{{0xFF, 0xFF, 0x00}}));
        assertTrue("PTP", UsbKinds.looksLikePhone(0x18D1, 0x4EE5, 0, new int[][]{{6, 1, 1}}));
        assertTrue("MTP + ADB", UsbKinds.looksLikePhone(0x18D1, 0x4EE2, 0, new int[][]{{0xFF, 0xFF, 0}, {0xFF, 0x42, 1}}));
        assertTrue("accessory mode", UsbKinds.looksLikePhone(0x18D1, 0x2D00, 0, new int[][]{{0xFF, 0xFF, 0xFF}}));
        assertTrue("USB tethering", UsbKinds.looksLikePhone(0x04E8, 0x6863, 0, new int[][]{{0xE0, 1, 3}, {0x0A, 0, 0}}));
    }

    @Test
    public void hubsStorageHidAndAudioAreNotPhones() {
        assertFalse(UsbKinds.looksLikePhone(0x1D6B, 0x0002, 9, new int[][]{{9, 0, 0}}));
        assertFalse(UsbKinds.looksLikePhone(0x0781, 0x5581, 0, new int[][]{{8, 6, 0x50}}));
        assertFalse(UsbKinds.looksLikePhone(0x046D, 0xC52B, 0, new int[][]{{3, 1, 1}, {3, 1, 2}}));
        assertFalse(UsbKinds.looksLikePhone(0x0D8C, 0x0014, 0, new int[][]{{1, 1, 0}, {1, 2, 0}}));
    }
}
