package me.ri3d.openauto.carplay.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothAdapter;

import org.junit.Test;

/** The Bluetooth summary keeps its four ways of failing apart; the first one that applies is named. */
public class TransportReportTest {
    private static final String REAL = "00:11:22:aa:BB:cc";

    @Test
    public void noAdapterIsNotProofOfNoHardware() {
        String s = TransportReport.status(false, false, BluetoothAdapter.STATE_OFF, null);
        assertTrue(s, s.startsWith("no adapter exposed") && s.contains("does not prove the device has no Bluetooth hardware"));
    }

    @Test
    public void permissionDeniedComesBeforeDisabled() {
        assertTrue(TransportReport.status(true, false, BluetoothAdapter.STATE_OFF, null).startsWith("permission denied"));
    }

    @Test
    public void disabledNamesTheState() {
        assertTrue(TransportReport.status(true, true, BluetoothAdapter.STATE_OFF, REAL).startsWith("Bluetooth disabled (off)"));
        assertTrue(TransportReport.status(true, true, BluetoothAdapter.STATE_TURNING_ON, REAL).startsWith("Bluetooth disabled (turning on)"));
    }

    @Test
    public void unavailableAddressIsSeparateFromTheRest() {
        assertEquals("on, adapter address unavailable: 02:00:00:00:00:00 is the placeholder Android 6 and newer give apps without LOCAL_MAC_ADDRESS",
                TransportReport.status(true, true, BluetoothAdapter.STATE_ON, "02:00:00:00:00:00"));
        assertEquals("on, adapter address unavailable: none returned", TransportReport.status(true, true, BluetoothAdapter.STATE_ON, null));
        assertEquals("on, adapter address unavailable: \"00:11:22:33:44\" is not a Bluetooth address",
                TransportReport.status(true, true, BluetoothAdapter.STATE_ON, "00:11:22:33:44"));
        assertEquals("on, adapter address available", TransportReport.status(true, true, BluetoothAdapter.STATE_ON, REAL));
        assertNull(TransportReport.problem(REAL));
    }
}
