package me.ri3d.openauto;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import me.ri3d.openauto.diag.DeviceInfo;
import me.ri3d.openauto.settings.Prefs;
import me.ri3d.openauto.ui.Dialogs;
import me.ri3d.openauto.ui.IconView;
import me.ri3d.openauto.ui.Insets;
import me.ri3d.openauto.ui.Ui;

/** The Type B launcher: header, two large mode tiles, Self Mode and Settings stacked on the right. */
public class LauncherActivity extends Activity implements ConnectionManager.Listener {
    private View wireless, wired, self, settings;
    private View minimize;
    private View statusDot;
    private TextView statusText;
    private ConnectionManager cm;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        Insets.systemBars(getWindow());
        Insets.fit(findViewById(R.id.root));
        cm = ConnectionManager.get(this);
        prefs = new Prefs(this);
        wireless = findViewById(R.id.tile_wireless);
        wired = findViewById(R.id.tile_wired);
        self = findViewById(R.id.tile_self);
        settings = findViewById(R.id.tile_settings);
        statusDot = findViewById(R.id.status_dot);
        statusText = (TextView) findViewById(R.id.status_text);
        minimize = findViewById(R.id.minimize);

        setMain(wireless, IconView.WIFI, R.string.wireless);
        setMain(wired, IconView.USB, R.string.wired);
        setSmall(self, IconView.SELF, R.string.self_mode, R.string.self_mode_sub);
        setSmall(settings, IconView.SLIDERS, R.string.settings, R.string.settings_sub);

        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        self.setOnClickListener(v -> onSelfMode());
        wireless.setOnClickListener(v -> onWireless());
        wired.setOnClickListener(v -> onWired(null));
        minimize.setOnClickListener(v -> onMinimize());
        handleUsbIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbIntent(intent);
    }

    /**
     * Launched by the system because an accessory-mode phone appeared, or started with
     * {@code --es connect_ip <ip> [--ei connect_port <port>]} (adb/test automation) to connect over TCP.
     */
    private void handleUsbIntent(Intent intent) {
        if (intent == null) return;
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && prefs.autoStart()) onWired(device);
            return;
        }
        if (intent.getBooleanExtra("dump_threads", false)) {
            intent.removeExtra("dump_threads");
            ConnectionManager.dumpThreads();
        }
        String ip = intent.getStringExtra("connect_ip");
        if (ip != null && !cm.isBusy()) {
            intent.removeExtra("connect_ip");
            cm.clearError();
            cm.connectTcp(ip, intent.getIntExtra("connect_port", Prefs.DEFAULT_PORT));
            ProjectionActivity.open(this);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        cm.setUiFocused(hasFocus); // a system dialog (USB permission) in front of us takes the focus
        if (hasFocus) Insets.systemBars(getWindow());
    }

    @Override
    protected void onStart() {
        super.onStart();
        String action = prefs.minimize();
        minimize.setVisibility("off".equals(action) ? View.GONE : View.VISIBLE);
        ((IconView) minimize.findViewById(R.id.minimize_icon)).setIcon("close".equals(action) ? IconView.CLOSE : IconView.MINIMIZE);
        cm.addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        cm.removeListener(this);
        super.onStop();
    }

    @Override
    public void onConnectionChanged(ConnectionManager m) {
        render();
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void onWired(UsbDevice device) {
        if (!cm.usbHostAvailable()) {
            Dialogs.info(this, getString(R.string.wired), getString(R.string.wired_no_host));
            return;
        }
        if (cm.isBusy()) {
            ProjectionActivity.open(this);
            return;
        }
        cm.clearError();
        cm.connectUsb(device);
        if (cm.phase() == ConnectionManager.Phase.ERROR) {
            Dialogs.info(this, getString(R.string.wired), cm.detail());
            return;
        }
        ProjectionActivity.open(this);
    }

    private void onWireless() {
        if (cm.isBusy()) {
            ProjectionActivity.open(this);
            return;
        }
        if (prefs.wirelessAuto() && cm.wirelessCapable()) {
            if (!Perms.hasHotspot(this)) {
                Perms.requestHotspot(this);
                return;
            }
            if (prefs.btPhone() == null) {
                Dialogs.info(this, getString(R.string.wireless), getString(R.string.err_bt_no_phone));
                return;
            }
            cm.clearError();
            cm.startWireless();
            if (cm.phase() == ConnectionManager.Phase.ERROR) {
                Dialogs.info(this, getString(R.string.wireless), cm.detail());
                return;
            }
            ProjectionActivity.open(this);
            return;
        }
        String ip = prefs.manualIp();
        if (ip.isEmpty()) {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra(SettingsActivity.EXTRA_CATEGORY, SettingsActivity.WIRELESS);
            startActivity(i);
            return;
        }
        cm.clearError();
        cm.connectTcp(ip, prefs.manualPort());
        ProjectionActivity.open(this);
    }

    /** Header button, Settings › General › Minimize button: leave the session running, or end it and close (or hidden). */
    private void onMinimize() {
        if ("close".equals(prefs.minimize())) {
            cm.stop();
            if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask(); else finish();
        } else {
            moveTaskToBack(true);
        }
    }

    private void onSelfMode() {
        String reason = SelfMode.unavailableReason(this);
        if (reason != null) {
            Dialogs.info(this, getString(R.string.self_title), reason);
            return;
        }
        if (cm.isBusy()) {
            ProjectionActivity.open(this);
            return;
        }
        Intent aa = SelfMode.openAndroidAuto(this);
        Dialogs.builder(this).setTitle(R.string.self_title).setMessage(R.string.self_howto)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.self_open_aa, (d, w) -> { if (aa != null) startActivity(aa); })
                .setPositiveButton(R.string.connect, (d, w) -> {
                    cm.clearError();
                    cm.connectTcp("127.0.0.1", SelfMode.HEAD_UNIT_SERVER_PORT);
                    ProjectionActivity.open(this);
                }).show();
    }

    // ---- rendering -----------------------------------------------------------------------------

    private void render() {
        Resources r = getResources();
        boolean usbHost = cm.usbHostAvailable();
        boolean phoneOnUsb = usbHost && cm.findUsbCandidate() != null;
        boolean bt = DeviceInfo.hasBluetooth();
        String manualIp = prefs.manualIp();
        ConnectionManager.Phase p = cm.phase();
        boolean busy = cm.isBusy();

        boolean connected = cm.isConnected();
        int wiredStatus = !usbHost ? R.string.wired_no_host
                : connected && cm.isUsb() ? R.string.status_return
                : busy && cm.isUsb() ? R.string.status_connecting
                : phoneOnUsb ? R.string.wired_found : R.string.wired_plug_in;
        boolean autoWireless = prefs.wirelessAuto() && cm.wirelessCapable();
        String wirelessStatus = p == ConnectionManager.Phase.WIRELESS_WAITING ? getString(R.string.wireless_waiting)
                : connected && !cm.isUsb() ? getString(R.string.status_return)
                : busy && !cm.isUsb() ? getString(R.string.status_connecting)
                : autoWireless ? getString(R.string.wireless_auto_waiting)
                : !manualIp.isEmpty() ? getString(R.string.wireless_tap_manual, manualIp)
                : bt ? getString(R.string.wireless_setup_first) : getString(R.string.wireless_no_bt);

        // The accent tile follows the detected phone, as in the reference's two states.
        styleMain(wireless, !phoneOnUsb, wirelessStatus, busy && !cm.isUsb());
        styleMain(wired, phoneOnUsb, getString(wiredStatus), phoneOnUsb || (busy && cm.isUsb()));

        String head;
        int color;
        boolean dot;
        switch (p) {
            case PROJECTING: // the launcher is showing, so nothing is being projected right now
            case READY: head = getString(R.string.status_connected); color = R.color.fg; dot = true; break;
            case ERROR: head = cm.detail() == null ? getString(R.string.status_error) : cm.detail(); color = R.color.danger; dot = false; break;
            case RECONNECTING: head = getString(R.string.status_reconnecting); color = R.color.accent; dot = false; break;
            case IDLE:
                head = getString(phoneOnUsb ? R.string.status_phone_usb : R.string.status_no_phone);
                color = phoneOnUsb ? R.color.fg : R.color.muted;
                dot = phoneOnUsb;
                break;
            default: head = getString(R.string.status_connecting); color = R.color.fg; dot = false;
        }
        statusText.setText(head);
        statusText.setTextColor(r.getColor(color));
        int ring = r.getColor(p == ConnectionManager.Phase.IDLE && !phoneOnUsb ? R.color.muted : R.color.accent);
        Ui.dot(statusDot, ring, dot ? ring : 0);
    }

    private void setMain(View tile, int icon, int title) {
        ((IconView) tile.findViewById(R.id.icon)).setIcon(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
    }

    private void setSmall(View tile, int icon, int title, int sub) {
        ((IconView) tile.findViewById(R.id.icon)).setIcon(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        ((TextView) tile.findViewById(R.id.subtitle)).setText(sub);
    }

    /** Accent (golden) or dark variant of a large tile, as in the reference's two states. */
    private void styleMain(View tile, boolean accent, String status, boolean ready) {
        Resources r = getResources();
        int fg = r.getColor(accent ? R.color.on_accent : R.color.fg);
        int sub = r.getColor(accent ? R.color.on_accent_sub : R.color.muted);
        tile.setBackgroundResource(accent ? R.drawable.bg_tile_accent : R.drawable.bg_tile);
        tile.findViewById(R.id.well).setBackgroundResource(accent ? R.drawable.bg_well_on_accent : R.drawable.bg_well);
        ((IconView) tile.findViewById(R.id.icon)).setColor(accent ? fg : r.getColor(R.color.accent));
        Ui.ring(tile.findViewById(R.id.arrow_ring), sub);
        ((IconView) tile.findViewById(R.id.arrow)).setColor(sub);
        ((TextView) tile.findViewById(R.id.title)).setTextColor(fg);
        ((TextView) tile.findViewById(R.id.subtitle)).setTextColor(fg);
        TextView st = (TextView) tile.findViewById(R.id.status);
        st.setText(status);
        st.setTextColor(sub);
        int ring = accent ? fg : r.getColor(R.color.muted);
        Ui.dot(tile.findViewById(R.id.dot), ring, ready ? ring : 0);
    }
}
