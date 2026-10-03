package me.ri3d.openauto.aa;

/**
 * Wire constants of the head-unit protocol. Every value is transcribed from docs/protocol-reference.md
 * (observed in aasdk/openauto); the section numbers below point there.
 */
public final class Wire {
    private Wire() {}

    // §1.1 channel ids
    public static final int CH_CONTROL = 0, CH_INPUT = 1, CH_SENSOR = 2, CH_VIDEO = 3, CH_MEDIA_AUDIO = 4,
            CH_SPEECH_AUDIO = 5, CH_SYSTEM_AUDIO = 6, CH_AV_INPUT = 7, CH_BLUETOOTH = 8;

    // §1.1 frame flags (byte 1)
    public static final int FRAME_MIDDLE = 0, FRAME_FIRST = 1, FRAME_LAST = 2, FRAME_BULK = 3;
    public static final int FLAG_CONTROL = 0x04, FLAG_ENCRYPTED = 0x08;

    // §1.3 maximum plaintext bytes per frame
    public static final int MAX_FRAME_PAYLOAD = 0x4000;

    // §2.1 control channel message ids
    public static final int VERSION_REQUEST = 0x0001, VERSION_RESPONSE = 0x0002, SSL_HANDSHAKE = 0x0003,
            AUTH_COMPLETE = 0x0004, SERVICE_DISCOVERY_REQUEST = 0x0005, SERVICE_DISCOVERY_RESPONSE = 0x0006,
            CHANNEL_OPEN_REQUEST = 0x0007, CHANNEL_OPEN_RESPONSE = 0x0008, PING_REQUEST = 0x000b,
            PING_RESPONSE = 0x000c, NAVIGATION_FOCUS_REQUEST = 0x000d, NAVIGATION_FOCUS_RESPONSE = 0x000e,
            SHUTDOWN_REQUEST = 0x000f, SHUTDOWN_RESPONSE = 0x0010, VOICE_SESSION_REQUEST = 0x0011,
            AUDIO_FOCUS_REQUEST = 0x0012, AUDIO_FOCUS_RESPONSE = 0x0013;

    // §2.2 version
    public static final int VERSION_MAJOR = 1, VERSION_MINOR = 1, VERSION_STATUS_MATCH = 0;

    // §2.4 StatusEnum
    public static final int STATUS_OK = 0, STATUS_FAIL = 1;

    // §2.4 audio focus
    public static final int AUDIO_FOCUS_GAIN = 1, AUDIO_FOCUS_GAIN_TRANSIENT = 2, AUDIO_FOCUS_GAIN_NAVI = 3,
            AUDIO_FOCUS_RELEASE = 4;
    public static final int AUDIO_FOCUS_STATE_GAIN = 1, AUDIO_FOCUS_STATE_GAIN_TRANSIENT = 2, AUDIO_FOCUS_STATE_LOSS = 3,
            AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY = 7;

    // §3 channel descriptor enums
    public static final int STREAM_AUDIO = 1, STREAM_VIDEO = 3;
    public static final int AUDIO_TYPE_SPEECH = 1, AUDIO_TYPE_SYSTEM = 2, AUDIO_TYPE_MEDIA = 3;
    public static final int RES_480P = 1, RES_720P = 2, RES_1080P = 3;
    // aasdk names these _30 = 1 and _60 = 2, but a real phone (protocol 1.7) sent ~55-59 fps for value 1.
    // Measured on 2026-10-03, so the values are the other way round: 1 = 60 fps, 2 = 30 fps.
    public static final int FPS_60 = 1, FPS_30 = 2;
    public static final int SENSOR_NIGHT_DATA = 10, SENSOR_DRIVING_STATUS = 13;
    public static final int DRIVING_UNRESTRICTED = 0;

    // §4.1 AV channel message ids
    public static final int AV_MEDIA_WITH_TIMESTAMP = 0x0000, AV_MEDIA = 0x0001, AV_SETUP_REQUEST = 0x8000,
            AV_START = 0x8001, AV_STOP = 0x8002, AV_SETUP_RESPONSE = 0x8003, AV_MEDIA_ACK = 0x8004,
            AV_INPUT_OPEN_REQUEST = 0x8005, AV_INPUT_OPEN_RESPONSE = 0x8006, VIDEO_FOCUS_REQUEST = 0x8007,
            VIDEO_FOCUS_INDICATION = 0x8008;
    public static final int AV_SETUP_FAIL = 1, AV_SETUP_OK = 2;
    public static final int VIDEO_FOCUS_FOCUSED = 1, VIDEO_FOCUS_UNFOCUSED = 2;

    // §5.1 sensor channel
    public static final int SENSOR_START_REQUEST = 0x8001, SENSOR_START_RESPONSE = 0x8002, SENSOR_EVENT = 0x8003;

    // §6.1 input channel
    public static final int INPUT_EVENT = 0x8001, BINDING_REQUEST = 0x8002, BINDING_RESPONSE = 0x8003;
    public static final int TOUCH_PRESS = 0, TOUCH_RELEASE = 1, TOUCH_DRAG = 2;
    // §6.2 ButtonCode
    public static final int BTN_HOME = 0x03, BTN_BACK = 0x04, BTN_MICROPHONE_1 = 0x54, BTN_TOGGLE_PLAY = 0x55,
            BTN_NEXT = 0x57, BTN_PREV = 0x58, BTN_PLAY = 0x7E, BTN_PAUSE = 0x7F;

    // §7.1 bluetooth channel
    public static final int BT_PAIRING_REQUEST = 0x8001, BT_PAIRING_RESPONSE = 0x8002;
    public static final int BT_PAIRING_OK = 1, BT_PAIRING_FAIL = 2;

    public static String channelName(int ch) {
        switch (ch) {
            case CH_CONTROL: return "control";
            case CH_INPUT: return "input";
            case CH_SENSOR: return "sensor";
            case CH_VIDEO: return "video";
            case CH_MEDIA_AUDIO: return "media-audio";
            case CH_SPEECH_AUDIO: return "speech-audio";
            case CH_SYSTEM_AUDIO: return "system-audio";
            case CH_AV_INPUT: return "mic";
            case CH_BLUETOOTH: return "bluetooth";
            default: return "ch" + ch;
        }
    }
}
