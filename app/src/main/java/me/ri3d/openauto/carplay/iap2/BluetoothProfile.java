package me.ri3d.openauto.carplay.iap2;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;

/**
 * The one transport profile implemented: iAP2 over Bluetooth RFCOMM carrying the control session
 * only. It advertises identification and authentication and nothing else: no CarPlay, no External
 * Accessory protocol, no HID, no power. Field IDs, encodings, sources and how sure each one is:
 * docs/iap2-reference.md. Not yet tried with an iPhone.
 */
public final class BluetoothProfile {
    /** Sent before the first SYN; a phone that speaks iAP2 answers with the same six bytes. */
    public static final byte[] DETECT = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};
    public static final int CONTROL_SESSION_ID = 10;
    public static final int CONTROL_SESSION_VERSION = 1;

    private BluetoothProfile() {}

    /**
     * Link version 1; up to 5 packets of up to 4096 bytes in flight towards this side; retransmit
     * after 2000 ms, at most 30 times; acknowledge within 22 ms or after 3 packets; control session
     * 10, version 1. Values taken from public implementations, not from Apple.
     */
    public static Iap2LinkLayer.Parameters linkProposal() {
        return new Iap2LinkLayer.Parameters(1, 5, 4096, 2000, 22, 30, 3,
                new Iap2LinkLayer.SessionEntry(CONTROL_SESSION_ID, Iap2LinkLayer.CONTROL_SESSION_TYPE, CONTROL_SESSION_VERSION));
    }

    /** IdentificationInformation for this profile; immutable, every field checked when built. */
    public static final class Identification implements IdentificationPayload {
        public static final String DEFAULT_NAME = "OpenAuto";
        /** The receiver's user interface is English only. */
        public static final String LANGUAGE = "en";
        /** UTF-8 bytes per text value, terminator included. A local bound, not a protocol value. */
        public static final int MAX_TEXT_BYTES = 64;

        static final int NAME = 0, MODEL_IDENTIFIER = 1, MANUFACTURER = 2, SERIAL_NUMBER = 3, FIRMWARE_VERSION = 4, HARDWARE_VERSION = 5,
                MESSAGES_SENT_BY_ACCESSORY = 6, MESSAGES_RECEIVED_FROM_DEVICE = 7, POWER_PROVIDING_CAPABILITY = 8,
                MAXIMUM_CURRENT_DRAWN_FROM_DEVICE = 9, CURRENT_LANGUAGE = 12, SUPPORTED_LANGUAGE = 13, BLUETOOTH_TRANSPORT_COMPONENT = 17;
        static final int COMPONENT_IDENTIFIER = 0, COMPONENT_NAME = 1, COMPONENT_SUPPORTS_IAP2 = 2, COMPONENT_BLUETOOTH_ADDRESS = 3;
        static final int COMPONENT_ID = 0;
        static final String COMPONENT = "Bluetooth";
        private static final Charset UTF8 = Charset.forName("UTF-8");

        private final String name, model, manufacturer, serial, firmware, hardware;
        private final byte[] address;

        /**
         * @param name             shown on the phone; the only value a rejection may change (back to {@link #DEFAULT_NAME})
         * @param bluetoothAddress this receiver's own adapter address, 6 bytes; mandatory for this transport
         */
        public Identification(String name, String model, String manufacturer, String serial, String firmware, String hardware,
                              byte[] bluetoothAddress) {
            this.name = text("Name", name);
            this.model = text("ModelIdentifier", model);
            this.manufacturer = text("Manufacturer", manufacturer);
            this.serial = text("SerialNumber", serial);
            this.firmware = text("FirmwareVersion", firmware);
            this.hardware = text("HardwareVersion", hardware);
            if (bluetoothAddress == null || bluetoothAddress.length != 6) {
                throw new IllegalArgumentException("Bluetooth address must be 6 bytes");
            }
            if (Arrays.equals(bluetoothAddress, new byte[6]) || Arrays.equals(bluetoothAddress, new byte[]{2, 0, 0, 0, 0, 0})) {
                throw new IllegalArgumentException("Bluetooth address unavailable (all zero, or Android's 02:00:00:00:00:00 placeholder); "
                        + "it is mandatory for this transport");
            }
            this.address = bluetoothAddress.clone();
        }

        @Override
        public CsmMessage message() {
            List<Parameter> p = new ArrayList<>();
            p.add(text(NAME, name));
            p.add(text(MODEL_IDENTIFIER, model));
            p.add(text(MANUFACTURER, manufacturer));
            p.add(text(SERIAL_NUMBER, serial));
            p.add(text(FIRMWARE_VERSION, firmware));
            p.add(text(HARDWARE_VERSION, hardware));
            // Lists of the control session messages used beyond identification and authentication: none.
            p.add(new Parameter(MESSAGES_SENT_BY_ACCESSORY, new byte[0]));
            p.add(new Parameter(MESSAGES_RECEIVED_FROM_DEVICE, new byte[0]));
            p.add(new Parameter(POWER_PROVIDING_CAPABILITY, new byte[]{0})); // none
            p.add(new Parameter(MAXIMUM_CURRENT_DRAWN_FROM_DEVICE, new byte[]{0, 0})); // mA
            p.add(text(CURRENT_LANGUAGE, LANGUAGE));
            p.add(text(SUPPORTED_LANGUAGE, LANGUAGE));
            p.add(new Parameter(BLUETOOTH_TRANSPORT_COMPONENT, CsmMessage.encodeParameters(Arrays.asList(
                    new Parameter(COMPONENT_IDENTIFIER, new byte[]{0, COMPONENT_ID}),
                    text(COMPONENT_NAME, COMPONENT),
                    new Parameter(COMPONENT_SUPPORTS_IAP2, new byte[0]),
                    new Parameter(COMPONENT_BLUETOOTH_ADDRESS, address)))));
            return new CsmMessage(ControlSession.IDENTIFICATION_INFORMATION, p);
        }

        @Override
        public Set<Integer> groupIds() {
            return Collections.singleton(BLUETOOTH_TRANSPORT_COMPONENT);
        }

        /** Only a rejected Name has a correction: the plain default. Every other field is mandatory as it is. */
        @Override
        public IdentificationPayload correctedFor(Set<Integer> rejectedIds) throws Uncorrectable {
            for (int id : rejectedIds) {
                if (id != NAME) throw new Uncorrectable(field(id) + " (" + id + ") rejected: " + whyFixed(id));
            }
            if (name.equals(DEFAULT_NAME)) throw new Uncorrectable("Name (0) rejected, and it already is the default \"" + DEFAULT_NAME + "\"");
            return new Identification(DEFAULT_NAME, model, manufacturer, serial, firmware, hardware, address);
        }

        private static String whyFixed(int id) {
            switch (id) {
                case MODEL_IDENTIFIER:
                case MANUFACTURER:
                case SERIAL_NUMBER:
                case FIRMWARE_VERSION:
                case HARDWARE_VERSION:
                    return "an identity value with no supported correction; change the configured value and reconnect";
                case MESSAGES_SENT_BY_ACCESSORY:
                case MESSAGES_RECEIVED_FROM_DEVICE:
                    return "it lists exactly what this receiver implements";
                case POWER_PROVIDING_CAPABILITY:
                case MAXIMUM_CURRENT_DRAWN_FROM_DEVICE:
                    return "a Bluetooth accessory neither provides nor draws power";
                case CURRENT_LANGUAGE:
                case SUPPORTED_LANGUAGE:
                    return "English is the receiver's only language";
                case BLUETOOTH_TRANSPORT_COMPONENT:
                    return "mandatory for this transport profile";
                default:
                    return "not a parameter of this profile";
            }
        }

        static String field(int id) {
            switch (id) {
                case NAME: return "Name";
                case MODEL_IDENTIFIER: return "ModelIdentifier";
                case MANUFACTURER: return "Manufacturer";
                case SERIAL_NUMBER: return "SerialNumber";
                case FIRMWARE_VERSION: return "FirmwareVersion";
                case HARDWARE_VERSION: return "HardwareVersion";
                case MESSAGES_SENT_BY_ACCESSORY: return "MessagesSentByAccessory";
                case MESSAGES_RECEIVED_FROM_DEVICE: return "MessagesReceivedFromDevice";
                case POWER_PROVIDING_CAPABILITY: return "PowerProvidingCapability";
                case MAXIMUM_CURRENT_DRAWN_FROM_DEVICE: return "MaximumCurrentDrawnFromDevice";
                case CURRENT_LANGUAGE: return "CurrentLanguage";
                case SUPPORTED_LANGUAGE: return "SupportedLanguage";
                case BLUETOOTH_TRANSPORT_COMPONENT: return "BluetoothTransportComponent";
                default: return "parameter";
            }
        }

        /** UTF-8 with a terminating zero byte, as both public implementations write text. */
        private static Parameter text(int id, String s) {
            byte[] b = s.getBytes(UTF8);
            return new Parameter(id, Arrays.copyOf(b, b.length + 1));
        }

        private static String text(String field, String s) {
            if (s == null || s.isEmpty()) throw new IllegalArgumentException(field + " is empty");
            if (s.indexOf('\0') >= 0) throw new IllegalArgumentException(field + " contains a zero character");
            if (s.getBytes(UTF8).length + 1 > MAX_TEXT_BYTES) throw new IllegalArgumentException(field + " is longer than " + (MAX_TEXT_BYTES - 1) + " UTF-8 bytes");
            return s;
        }
    }
}
