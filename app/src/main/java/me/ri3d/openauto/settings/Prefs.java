package me.ri3d.openauto.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** All persisted settings, with the defaults chosen for a low-end head unit. */
public final class Prefs {
    public static final String RES = "video_res";            // 480 | 720 | 1080
    public static final String FPS = "video_fps";            // 30 | 60
    public static final String DPI = "video_dpi";            // 80..240
    public static final String FIT = "video_fit";            // fill | bars
    public static final String DECODER = "video_decoder";    // hw | sw
    public static final String VIDEO_OUT = "video_out";      // auto | direct | stream | gpu
    public static final String NIGHT = "night_mode";         // day | night | auto
    public static final String KEEP_ON = "keep_screen_on";
    public static final String AUTO_START = "auto_start";
    public static final String AUDIO_MEDIA = "audio_media";
    public static final String AUDIO_SPEECH = "audio_speech";
    public static final String MIC = "mic";
    public static final String MEDIA_KEYS = "media_keys";
    public static final String BACK_KEY = "back_key";        // exit | phone
    public static final String WIRELESS_AUTO = "wireless_auto";
    public static final String AP_SSID = "ap_ssid";
    public static final String AP_PASS = "ap_pass";
    public static final String MANUAL_IP = "manual_ip";
    public static final String MANUAL_PORT = "manual_port";
    public static final String AUTO_RECONNECT = "auto_reconnect";
    public static final String BT_PHONE = "bt_phone";        // MAC of the paired phone for wireless
    public static final String AREA_W = "area_w", AREA_H = "area_h"; // last measured projection stage, px
    public static final String NATIVE_TLS_PROBING = "native_tls_probing"; // set while the system OpenSSL self-test runs

    public static final int DEFAULT_RES = 480, DEFAULT_FPS = 30, DEFAULT_DPI = 140, DEFAULT_PORT = 5277;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("openauto", Context.MODE_PRIVATE);
    }

    public int res() { return sp.getInt(RES, DEFAULT_RES); }
    public int fps() { return sp.getInt(FPS, DEFAULT_FPS); }
    public int dpi() { return sp.getInt(DPI, DEFAULT_DPI); }
    public String fit() { return sp.getString(FIT, "fill"); }
    public String decoder() { return sp.getString(DECODER, "hw"); }
    public String videoOut() { return sp.getString(VIDEO_OUT, "auto"); }
    public String night() { return sp.getString(NIGHT, "auto"); }
    public boolean keepScreenOn() { return sp.getBoolean(KEEP_ON, true); }
    public boolean autoStart() { return sp.getBoolean(AUTO_START, true); }
    public boolean audioMedia() { return sp.getBoolean(AUDIO_MEDIA, true); }
    public boolean audioSpeech() { return sp.getBoolean(AUDIO_SPEECH, true); }
    public boolean mic() { return sp.getBoolean(MIC, true); }
    public boolean mediaKeys() { return sp.getBoolean(MEDIA_KEYS, true); }
    public String backKey() { return sp.getString(BACK_KEY, "exit"); }
    public boolean wirelessAuto() { return sp.getBoolean(WIRELESS_AUTO, false); }
    public String apSsid() { return sp.getString(AP_SSID, null); }
    public String apPass() { return sp.getString(AP_PASS, null); }
    public String manualIp() { return sp.getString(MANUAL_IP, ""); }
    public int manualPort() { return sp.getInt(MANUAL_PORT, DEFAULT_PORT); }
    public boolean autoReconnect() { return sp.getBoolean(AUTO_RECONNECT, true); }
    public String btPhone() { return sp.getString(BT_PHONE, null); }
    public int areaW() { return sp.getInt(AREA_W, 0); }
    public int areaH() { return sp.getInt(AREA_H, 0); }

    public void put(String key, int v) { sp.edit().putInt(key, v).apply(); }
    public void put(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public void put(String key, String v) { sp.edit().putString(key, v).apply(); }
    public void reset() { sp.edit().clear().apply(); }

    public SharedPreferences raw() { return sp; }
}
