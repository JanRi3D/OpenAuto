package me.ri3d.openauto.carplay.iap2;

import static me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import me.ri3d.openauto.carplay.iap2.IdentificationPayload.Uncorrectable;

/** The Bluetooth profile's identification: bounds on every field, the exact bytes, and which rejections it can correct. */
public class BluetoothProfileTest {
    private static final byte[] ADDRESS = hex("AC123456789A");

    @Test
    public void encodesTheProfileByteForByte() {
        byte[] expected = hex("4040" + "0077" + "1D01"
                + "0007" + "0000" + "4E4100"            // Name "NA"
                + "0007" + "0001" + "4D4F00"            // ModelIdentifier "MO"
                + "0007" + "0002" + "4D4100"            // Manufacturer "MA"
                + "0007" + "0003" + "534E00"            // SerialNumber "SN"
                + "0007" + "0004" + "465700"            // FirmwareVersion "FW"
                + "0007" + "0005" + "485700"            // HardwareVersion "HW"
                + "0004" + "0006"                       // MessagesSentByAccessory: none
                + "0004" + "0007"                       // MessagesReceivedFromDevice: none
                + "0005" + "0008" + "00"                // PowerProvidingCapability: none
                + "0006" + "0009" + "0000"              // MaximumCurrentDrawnFromDevice: 0 mA
                + "0007" + "000C" + "656E00"            // CurrentLanguage "en"
                + "0007" + "000D" + "656E00"            // SupportedLanguage "en"
                + "0026" + "0011"                       // BluetoothTransportComponent
                + "0006" + "0000" + "0000"              //   TransportComponentIdentifier 0
                + "000E" + "0001" + "426C7565746F6F746800" // TransportComponentName "Bluetooth"
                + "0004" + "0002"                       //   TransportSupportsiAP2Connection
                + "000A" + "0003" + "AC123456789A");    //   Bluetooth address
        assertArrayEquals(expected, profile("NA").message().encode());
        assertEquals(Collections.singleton(17), profile("NA").groupIds());
    }

    @Test
    public void refusesValuesOutsideItsBounds() {
        String longest = new String(new char[BluetoothProfile.Identification.MAX_TEXT_BYTES - 1]).replace('\0', 'x');
        profile(longest);
        expectIllegal(() -> profile(longest + "x"));
        expectIllegal(() -> profile(new String(new char[32]).replace('\0', 'ä'))); // 64 UTF-8 bytes plus the terminator
        expectIllegal(() -> profile(""));
        expectIllegal(() -> profile("a\0b"));
        expectIllegal(() -> profile(null));
        for (String address : new String[]{"000000000000", "020000000000", "AC1234", "AC123456789A00"}) {
            expectIllegal(() -> new BluetoothProfile.Identification("NA", "MO", "MA", "SN", "FW", "HW", hex(address)));
        }
    }

    @Test
    public void correctsOnlyAConfiguredNameBackToTheDefault() throws Exception {
        IdentificationPayload corrected = profile("Küchenradio").correctedFor(Collections.singleton(0));
        assertEquals("OpenAuto\0", new String(corrected.message().parameters().get(0).value(), "UTF-8"));
        assertEquals("only the name differs", Collections.singleton(0),
                ControlSession.changedIds(profile("Küchenradio").message(), corrected.message(), corrected.groupIds()));
        expectUncorrectable(profile(BluetoothProfile.Identification.DEFAULT_NAME), set(0), "already is the default");
        String[][] fixed = {{"1", "ModelIdentifier"}, {"2", "Manufacturer"}, {"3", "SerialNumber"}, {"4", "FirmwareVersion"},
                {"5", "HardwareVersion"}, {"6", "MessagesSentByAccessory"}, {"7", "MessagesReceivedFromDevice"},
                {"8", "PowerProvidingCapability"}, {"9", "MaximumCurrentDrawnFromDevice"}, {"12", "CurrentLanguage"},
                {"13", "SupportedLanguage"}, {"17", "BluetoothTransportComponent"}};
        for (String[] f : fixed) {
            expectUncorrectable(profile("Küchenradio"), set(Integer.parseInt(f[0])), f[1] + " (" + f[0] + ") rejected");
            expectUncorrectable(profile("Küchenradio"), set(0, Integer.parseInt(f[0])), f[1] + " (" + f[0] + ") rejected");
        }
    }

    private static BluetoothProfile.Identification profile(String name) {
        return new BluetoothProfile.Identification(name, "MO", "MA", "SN", "FW", "HW", ADDRESS);
    }

    private static Set<Integer> set(Integer... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    private static void expectUncorrectable(IdentificationPayload payload, Set<Integer> rejected, String reason) {
        try {
            payload.correctedFor(rejected);
            fail(rejected + " corrected");
        } catch (Uncorrectable e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
    }

    private static void expectIllegal(Runnable r) {
        try {
            r.run();
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // outside the profile's bounds
        }
    }
}
