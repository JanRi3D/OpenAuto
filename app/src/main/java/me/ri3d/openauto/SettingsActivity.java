package me.ri3d.openauto;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.ri3d.openauto.aa.NativeTls;
import me.ri3d.openauto.diag.CryptoBench;
import me.ri3d.openauto.diag.Decoders;
import me.ri3d.openauto.diag.DeviceInfo;
import me.ri3d.openauto.settings.Prefs;
import me.ri3d.openauto.settings.Rows;
import me.ri3d.openauto.ui.BarlowText;
import me.ri3d.openauto.ui.Dialogs;
import me.ri3d.openauto.ui.Fonts;
import me.ri3d.openauto.ui.IconView;
import me.ri3d.openauto.ui.Insets;
import me.ri3d.openauto.wireless.Hotspot;

/** Left navigation + right content panel. Rows are built from the implemented settings only. */
public class SettingsActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    static final int GENERAL = 0, VIDEO = 1, AUDIO = 2, INPUT = 3, BLUETOOTH = 4, WIRELESS = 5, DIAGNOSTICS = 6;
    private static final int[] LABELS = {R.string.cat_general, R.string.cat_video, R.string.cat_audio, R.string.cat_input,
            R.string.cat_bluetooth, R.string.cat_wireless, R.string.cat_diagnostics};
    private static final int[] ICONS = {IconView.SLIDERS, IconView.MONITOR, IconView.SPEAKER, IconView.TARGET,
            IconView.BLUETOOTH, IconView.WIFI, IconView.ACTIVITY};
    static final int[][] RES_SIZES = {{800, 480}, {1280, 720}, {1920, 1080}};
    static final int[] RES_VALUES = {480, 720, 1080};

    private LinearLayout nav, rows;
    private Prefs prefs;
    private int current = -1;
    private int generation; // guards async row builders against a category switch
    private final Handler ui = new Handler();

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        current = -1;
        show(requestCode == Perms.REQ_BT ? BLUETOOTH : AUDIO);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Insets.systemBars(getWindow());
        Insets.fit(findViewById(R.id.root));
        prefs = new Prefs(this);
        nav = (LinearLayout) findViewById(R.id.nav);
        rows = (LinearLayout) findViewById(R.id.rows);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            View item = inf.inflate(R.layout.nav_item, nav, false);
            ((IconView) item.findViewById(R.id.icon)).setIcon(ICONS[i]);
            ((TextView) item.findViewById(R.id.label)).setText(LABELS[i]);
            item.setOnClickListener(v -> show(idx));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) item.getLayoutParams();
            if (i > 0) lp.topMargin = getResources().getDimensionPixelSize(R.dimen.nav_gap);
            nav.addView(item, lp);
        }
        show(getIntent().getIntExtra(EXTRA_CATEGORY, VIDEO));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Insets.systemBars(getWindow());
    }

    private void show(int cat) {
        if (cat == current) return;
        current = cat;
        generation++;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View item = nav.getChildAt(i);
            boolean on = i == cat;
            item.setBackgroundResource(on ? R.drawable.bg_nav_active : R.drawable.bg_nav);
            ((IconView) item.findViewById(R.id.icon)).setColor(getResources().getColor(on ? R.color.accent : R.color.muted));
            BarlowText label = (BarlowText) item.findViewById(R.id.label);
            label.setTextColor(getResources().getColor(on ? R.color.fg : R.color.muted));
            label.setFont(on ? Fonts.SEMIBOLD : Fonts.MEDIUM);
        }
        rows.removeAllViews();
        switch (cat) {
            case GENERAL: buildGeneral(); break;
            case VIDEO: buildVideo(); break;
            case AUDIO: buildAudio(); break;
            case INPUT: buildInput(); break;
            case BLUETOOTH: buildBluetooth(); break;
            case WIRELESS: buildWireless(); break;
            default: buildDiagnostics(); break;
        }
    }

    private void add(View row) {
        if (rows.getChildCount() > 0) rows.addView(Rows.divider(this));
        rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout reconnectNote(LinearLayout row) {
        Rows.note(row, getString(R.string.reconnect_note), R.color.muted);
        return row;
    }

    // ---- General ------------------------------------------------------------------------------

    private static final String[] BARS = {"notch", "status", "full", "normal"};
    private static final int[] BAR_LABELS = {R.string.bars_notch, R.string.bars_status, R.string.bars_full, R.string.bars_normal};

    private void buildGeneral() {
        add(Rows.toggle(this, s(R.string.set_keep_screen_on), s(R.string.set_keep_screen_on_desc), prefs.keepScreenOn(), null,
                i -> prefs.put(Prefs.KEEP_ON, i == 1)));
        add(Rows.toggle(this, s(R.string.set_auto_start), s(R.string.set_auto_start_desc), prefs.autoStart(), null,
                i -> prefs.put(Prefs.AUTO_START, i == 1)));
        int bars = Math.max(0, indexOf(BARS, prefs.systemBars()));
        LinearLayout barsRow = Rows.action(this, s(R.string.set_bars), s(BAR_LABELS[bars]), v -> pickBars(bars));
        Rows.note(barsRow, s(Build.VERSION.SDK_INT >= 19 ? R.string.bars_note : R.string.bars_old_android), R.color.muted);
        add(barsRow);
        String[] minimize = {"minimize", "close", "off"};
        add(Rows.choice(this, s(R.string.set_minimize), s(R.string.set_minimize_desc),
                new CharSequence[]{s(R.string.minimize), s(R.string.minimize_close), s(R.string.minimize_off)},
                Math.max(0, indexOf(minimize, prefs.minimize())), null, i -> prefs.put(Prefs.MINIMIZE, minimize[i])));
        add(Rows.action(this, s(R.string.set_diagnostics), s(R.string.set_diagnostics_desc), v -> show(DIAGNOSTICS)));
        add(Rows.action(this, s(R.string.set_reset), s(R.string.set_reset_desc), v -> {
            prefs.reset();
            Insets.systemBars(getWindow());
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
            current = -1;
            show(GENERAL);
        }));
    }

    private void pickBars(int selected) {
        CharSequence[] labels = new CharSequence[BARS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = s(BAR_LABELS[i]);
        Dialogs.builder(this).setTitle(R.string.set_bars).setSingleChoiceItems(labels, selected, (d, w) -> {
            d.dismiss();
            prefs.put(Prefs.SYSTEM_BARS, BARS[w]);
            Insets.systemBars(getWindow());
            current = -1;
            show(GENERAL);
        }).show();
    }

    // ---- Video --------------------------------------------------------------------------------

    private static final String[] OUTPUTS = {"auto", "direct", "stream", "gpu"};

    private void buildVideo() {
        final int gen = generation;
        final LinearLayout placeholder = Rows.row(this, s(R.string.set_resolution), s(R.string.diag_probe_running));
        add(placeholder);
        // The decoder probe configures a real MediaCodec per size; keep it off the UI thread.
        new Thread(() -> {
            final String[] reasons = new String[RES_VALUES.length];
            for (int i = 0; i < RES_VALUES.length; i++) {
                if (!Decoders.probe(RES_SIZES[i][0], RES_SIZES[i][1])) {
                    reasons[i] = getString(R.string.res_unsupported, label(i));
                }
            }
            ui.post(() -> {
                if (gen != generation) return;
                int sel = indexOf(RES_VALUES, prefs.res());
                LinearLayout row = Rows.choice(this, s(R.string.set_resolution), s(R.string.set_resolution_desc),
                        new CharSequence[]{s(R.string.res_480), s(R.string.res_720), s(R.string.res_1080)}, sel, reasons,
                        i -> prefs.put(Prefs.RES, RES_VALUES[i]));
                reconnectNote(row);
                int at = rows.indexOfChild(placeholder);
                rows.removeViewAt(at);
                rows.addView(row, at, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            });
        }, "decoder-probe").start();

        add(reconnectNote(Rows.choice(this, s(R.string.set_fps), s(R.string.set_fps_desc),
                new CharSequence[]{s(R.string.fps_30), s(R.string.fps_60)}, prefs.fps() == 60 ? 1 : 0, null,
                i -> prefs.put(Prefs.FPS, i == 1 ? 60 : 30))));
        add(reconnectNote(Rows.stepper(this, s(R.string.set_dpi), s(R.string.set_dpi_desc), 80, 240, 10, prefs.dpi(),
                v -> prefs.put(Prefs.DPI, v))));
        add(reconnectNote(Rows.choice(this, s(R.string.set_fit), s(R.string.set_fit_desc),
                new CharSequence[]{s(R.string.fit_fill), s(R.string.fit_bars)}, "bars".equals(prefs.fit()) ? 1 : 0, null,
                i -> prefs.put(Prefs.FIT, i == 1 ? "bars" : "fill"))));
        add(reconnectNote(Rows.choice(this, s(R.string.set_output), s(R.string.set_output_desc),
                new CharSequence[]{s(R.string.output_auto), s(R.string.output_direct), s(R.string.output_stream), s(R.string.output_gpu)},
                Math.max(0, indexOf(OUTPUTS, prefs.videoOut())), null, i -> prefs.put(Prefs.VIDEO_OUT, OUTPUTS[i]))));
        add(reconnectNote(Rows.choice(this, s(R.string.set_decoder), s(R.string.set_decoder_desc),
                new CharSequence[]{s(R.string.decoder_hw), s(R.string.decoder_sw)}, "sw".equals(prefs.decoder()) ? 1 : 0, null,
                i -> prefs.put(Prefs.DECODER, i == 1 ? "sw" : "hw"))));
        String[] nights = {"day", "night", "auto"};
        LinearLayout night = Rows.choice(this, s(R.string.set_night), s(R.string.set_night_desc),
                new CharSequence[]{s(R.string.night_day), s(R.string.night_night), s(R.string.night_auto)},
                Math.max(0, indexOf(nights, prefs.night())), null, i -> {
                    prefs.put(Prefs.NIGHT, nights[i]);
                    ConnectionManager.get(this).nightModeChanged();
                });
        Rows.note(night, s(R.string.night_auto_desc), R.color.muted);
        add(night);
    }

    private String label(int resIndex) {
        return getString(resIndex == 0 ? R.string.res_480 : resIndex == 1 ? R.string.res_720 : R.string.res_1080);
    }

    // ---- Audio --------------------------------------------------------------------------------

    private void buildAudio() {
        add(Rows.toggle(this, s(R.string.set_audio_media), s(R.string.set_audio_media_desc), prefs.audioMedia(), null,
                i -> prefs.put(Prefs.AUDIO_MEDIA, i == 1)));
        add(Rows.toggle(this, s(R.string.set_audio_speech), s(R.string.set_audio_speech_desc), prefs.audioSpeech(), null,
                i -> prefs.put(Prefs.AUDIO_SPEECH, i == 1)));
        boolean mic = DeviceInfo.hasMic(this);
        add(reconnectNote(Rows.toggle(this, s(R.string.set_mic), s(R.string.set_mic_desc), mic && prefs.mic(),
                mic ? null : getString(R.string.mic_unavailable), i -> prefs.put(Prefs.MIC, i == 1))));
        if (mic && !Perms.hasMic(this)) {
            add(Rows.action(this, s(R.string.mic_permission), s(R.string.mic_permission_desc), v -> Perms.requestMic(this)));
        }
    }

    // ---- Input --------------------------------------------------------------------------------

    private void buildInput() {
        add(Rows.toggle(this, s(R.string.set_media_keys), s(R.string.set_media_keys_desc), prefs.mediaKeys(), null,
                i -> prefs.put(Prefs.MEDIA_KEYS, i == 1)));
        add(Rows.choice(this, s(R.string.set_back_key), s(R.string.set_back_key_desc),
                new CharSequence[]{s(R.string.back_exit), s(R.string.back_phone)}, "phone".equals(prefs.backKey()) ? 1 : 0, null,
                i -> prefs.put(Prefs.BACK_KEY, i == 1 ? "phone" : "exit")));
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // guarded by Perms.hasBluetooth() (API 31+) and SecurityException handlers
    private void buildBluetooth() {
        if (!Perms.hasBluetooth(this)) {
            add(Rows.action(this, s(R.string.bt_permission), s(R.string.bt_permission_desc), v -> Perms.requestBluetooth(this)));
            return;
        }
        BluetoothAdapter bt = null;
        try {
            bt = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ignored) {
        }
        String state;
        if (bt == null) {
            state = getString(R.string.bt_none);
        } else if (!bt.isEnabled()) {
            state = getString(R.string.bt_off);
        } else {
            String name = null;
            try { name = bt.getName(); } catch (SecurityException ignored) { }
            state = getString(R.string.bt_on, name == null ? "" : name);
        }
        LinearLayout stateRow = Rows.row(this, s(R.string.set_bt_state), s(R.string.set_bt_state_desc));
        stateRow.addView(Rows.text(this, state, Fonts.MEDIUM, R.dimen.row_desc, R.color.segment_text));
        add(stateRow);
        if (bt == null) return;
        final BluetoothAdapter adapter = bt;
        add(Rows.action(this, s(R.string.set_bt_discoverable), s(R.string.set_bt_discoverable_desc), v -> {
            try {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                startActivity(i);
            } catch (RuntimeException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));
        String phone = prefs.btPhone();
        String phoneLabel = phone == null ? getString(R.string.bt_phone_none) : bondedName(adapter, phone);
        add(Rows.action(this, s(R.string.set_bt_phone), phoneLabel, v -> pickPhone(adapter)));
    }

    @SuppressLint("MissingPermission") // only reached from buildBluetooth()
    private String bondedName(BluetoothAdapter a, String mac) {
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (mac.equals(d.getAddress())) return d.getName() + " · " + mac;
            }
        } catch (SecurityException ignored) {
        }
        return mac;
    }

    @SuppressLint("MissingPermission") // only reached from buildBluetooth()
    private void pickPhone(BluetoothAdapter a) {
        final List<BluetoothDevice> devs = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) devs.addAll(bonded);
        } catch (SecurityException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (devs.isEmpty()) {
            Toast.makeText(this, R.string.bt_no_bonded, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = new CharSequence[devs.size()];
        for (int i = 0; i < names.length; i++) names[i] = devs.get(i).getName() + "\n" + devs.get(i).getAddress();
        Dialogs.builder(this).setTitle(R.string.bt_pick_title).setItems(names, (d, w) -> {
            prefs.put(Prefs.BT_PHONE, devs.get(w).getAddress());
            current = -1;
            show(BLUETOOTH);
        }).show();
    }

    // ---- Wireless -----------------------------------------------------------------------------

    private void buildWireless() {
        boolean bt = DeviceInfo.hasBluetooth();
        boolean ap = Hotspot.supported(this);
        String reason = !bt ? getString(R.string.wireless_auto_needs_bt) : !ap ? getString(R.string.wireless_auto_needs_ap) : null;
        add(Rows.toggle(this, s(R.string.set_wireless_auto), s(R.string.set_wireless_auto_desc), reason == null && prefs.wirelessAuto(),
                reason, i -> prefs.put(Prefs.WIRELESS_AUTO, i == 1)));
        if (reason == null && !Perms.hasHotspot(this)) {
            add(Rows.action(this, s(R.string.err_location_permission), s(R.string.set_hotspot_desc), v -> Perms.requestHotspot(this)));
        }
        if (ap) {
            if (prefs.apSsid() == null) {
                SecureRandom rnd = new SecureRandom(); // hotspot credentials, shown to the user; not a secret key
                prefs.put(Prefs.AP_SSID, "OPENAUTO-" + Integer.toHexString(0x1000 + rnd.nextInt(0xEFFF)).toUpperCase(Locale.ROOT));
                prefs.put(Prefs.AP_PASS, Long.toString(Math.abs(rnd.nextLong()), 36).substring(0, 10));
            }
            add(Rows.row(this, s(R.string.set_hotspot), prefs.apSsid() + "  ·  " + prefs.apPass()));
        }
        String ip = prefs.manualIp();
        add(Rows.action(this, s(R.string.set_manual), ip.isEmpty() ? s(R.string.set_manual_desc) : ip + ":" + prefs.manualPort(),
                v -> manualDialog()));
        add(Rows.toggle(this, s(R.string.set_auto_reconnect), s(R.string.set_auto_reconnect_desc), prefs.autoReconnect(), null,
                i -> prefs.put(Prefs.AUTO_RECONNECT, i == 1)));
    }

    private void manualDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = getResources().getDimensionPixelSize(R.dimen.panel_pad);
        box.setPadding(p, p / 2, p, 0);
        final EditText ipField = new EditText(this);
        ipField.setHint(R.string.manual_ip_hint);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setText(prefs.manualIp());
        final EditText portField = new EditText(this);
        portField.setHint(R.string.manual_port_hint);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setText(String.valueOf(prefs.manualPort()));
        box.addView(ipField);
        box.addView(portField);
        Dialogs.builder(this).setTitle(R.string.manual_title).setMessage(R.string.set_manual_desc).setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String ip = ipField.getText().toString().trim();
                    int port;
                    try {
                        port = Integer.parseInt(portField.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        port = Prefs.DEFAULT_PORT;
                    }
                    prefs.put(Prefs.MANUAL_IP, ip);
                    prefs.put(Prefs.MANUAL_PORT, port);
                    current = -1;
                    show(WIRELESS);
                    startManual(ip, port);
                }).show();
    }

    private void startManual(String ip, int port) {
        if (ip.isEmpty()) return;
        ConnectionManager cm = ConnectionManager.get(this);
        cm.clearError();
        cm.connectTcp(ip, port);
        ProjectionActivity.open(this);
    }

    // ---- Diagnostics --------------------------------------------------------------------------

    private void buildDiagnostics() {
        final int gen = generation;
        for (String[] kv : DeviceInfo.collect(this)) add(Rows.info(this, kv[0], kv[1]));
        final LinearLayout probe = Rows.info(this, s(R.string.diag_probe), s(R.string.diag_probe_running));
        add(probe);
        List<String> lines = ConnectionManager.get(this).logLines();
        StringBuilder logText = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 40); i < lines.size(); i++) logText.append(lines.get(i)).append((char) 10);
        LinearLayout logRow = Rows.info(this, s(R.string.diag_log), logText.length() == 0 ? s(R.string.diag_log_empty) : logText.toString().trim());
        ((TextView) logRow.getChildAt(1)).setGravity(Gravity.LEFT);
        add(logRow);
        final LinearLayout crypto = Rows.info(this, s(R.string.diag_crypto), s(R.string.diag_probe_running));
        add(crypto);
        new Thread(() -> {
            double system = ConnectionManager.get(this).nativeTlsSpeed();
            double[] mbps = CryptoBench.run();
            final String text = (system < 0 ? "System OpenSSL not used (" + NativeTls.status() + ")"
                    : String.format(Locale.US, "System %s: %.1f MB/s", NativeTls.status(), system))
                    + String.format(Locale.US, "; Java engine: AES-GCM %.2f MB/s, ChaCha20-Poly1305 %.2f MB/s", mbps[0], mbps[1]);
            Log.i("Connection", "crypto benchmark: " + text);
            ui.post(() -> {
                if (gen != generation) return;
                ((TextView) crypto.getChildAt(1)).setText(text);
            });
        }, "crypto-bench").start();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < RES_VALUES.length; i++) {
                boolean ok = Decoders.probe(RES_SIZES[i][0], RES_SIZES[i][1]);
                if (sb.length() > 0) sb.append(", ");
                sb.append(label(i)).append(ok ? " yes" : " no");
            }
            final String text = sb.toString();
            ui.post(() -> {
                if (gen != generation) return;
                ((TextView) probe.getChildAt(1)).setText(text);
            });
        }, "decoder-probe").start();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String s(int res) {
        return getString(res);
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }
}
