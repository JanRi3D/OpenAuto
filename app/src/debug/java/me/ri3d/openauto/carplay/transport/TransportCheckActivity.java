package me.ri3d.openauto.carplay.transport;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.ri3d.openauto.Perms;
import me.ri3d.openauto.carplay.auth.LocalMfiAuthenticator;
import me.ri3d.openauto.carplay.auth.ProbeRunner;
import me.ri3d.openauto.carplay.auth.SyntheticMfiProbe;
import me.ri3d.openauto.carplay.iap2.BluetoothProfile;
import me.ri3d.openauto.ui.Insets;

/**
 * The "Transport check" launcher entry of debug builds: what Android on this device exposes for the
 * transports CarPlay could use, saved as a report. It only reads until a button is pressed. USB:
 * access is asked for the one device selected, which is then opened just to read its raw
 * descriptors; no configuration is set, no interface claimed, no transfer sent. Bluetooth: an
 * RFCOMM check runs only when started for a selected paired phone, sends nothing but the iAP2
 * detection bytes, and stops at Cancel or its time limit. The iAP2 handshake, also started per
 * phone, is the one check that sends more: the Bluetooth profile's link, this radio's
 * identification and the certificate and signature of the SYNTHETIC identity the MFi probe left
 * behind ({@link HandshakeCheck}). No real accessory identity exists, so none is ever sent.
 * Blocking work runs on one worker ({@link ProbeRunner}); this instance owns its operation and
 * cancels it in {@link #onDestroy}.
 */
@SuppressLint("SetTextI18n") // debug-only diagnostic screen, English only
public final class TransportCheckActivity extends Activity {
    private static final String TAG = "TransportCheck";
    private static final String ACTION_USB_PERMISSION = "me.ri3d.openauto.TRANSPORT_CHECK_USB_PERMISSION";
    private static final long CONNECT_LIMIT_MS = 20000, WAIT_LIMIT_MS = 60000, HANDSHAKE_LIMIT_MS = 60000;
    private static ProbeRunner runner;

    private final List<String> checks = new ArrayList<>();
    private List<String> snapshot = new ArrayList<>();
    private String capturedAt;
    private LinearLayout top, actions;
    private Button allow, cancel;
    private TextView status, report;
    private ProbeRunner.Ticket current;
    /** Stops the running RFCOMM or handshake check; null while none runs. */
    private Runnable cancelCheck;
    private String usbAsked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        top = new LinearLayout(this);
        button(top, "Refresh", v -> refresh());
        allow = button(top, "Allow Bluetooth", v -> Perms.requestBluetooth(this));
        button(top, "Save report", v -> save());
        cancel = button(top, "Cancel", v -> cancelRfcomm());
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        content.addView(top);
        content.addView(actions);
        content.addView(status);
        content.addView(report);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        Insets.fit(scroll);
        listenForUsbAnswers();
        refresh();
    }

    /**
     * Android has no flag for this below API 33. A forged answer could only start {@link #readUsb},
     * which checks the access with Android again before opening anything.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void listenForUsbAnswers() {
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbAnswer, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbAnswer, f);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbAnswer);
        if (cancelCheck != null) cancelCheck.run();
        if (current != null) {
            runner().cancel(current);
            current = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != Perms.REQ_BT) return;
        StringBuilder sb = new StringBuilder(now() + " Bluetooth permission request answered:");
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            sb.append(' ').append(permissions[i].substring(permissions[i].lastIndexOf('.') + 1)).append(grantResults[i] == PackageManager.PERMISSION_GRANTED ? " granted" : " denied");
        }
        check(sb.toString());
        refresh();
    }

    /** Re-reads the passive part; checks already run stay in the report with their own times. */
    private void refresh() {
        List<String> lines = new ArrayList<>();
        TransportReport.device(lines);
        TransportReport.app(this, lines);
        List<BluetoothDevice> paired = TransportReport.bluetooth(this, lines);
        List<UsbDevice> usb = TransportReport.usb(this, lines);
        snapshot = lines;
        capturedAt = now();
        allow.setVisibility(Build.VERSION.SDK_INT >= 31 && !Perms.hasBluetooth(this) ? View.VISIBLE : View.GONE);
        actions.removeAllViews();
        for (final UsbDevice d : usb) {
            LinearLayout row = row("USB " + TransportReport.id(d) + " " + d.getDeviceName());
            button(row, "Read descriptors", v -> readUsb(d));
        }
        for (final BluetoothDevice d : paired) {
            LinearLayout row = row("Bluetooth " + TransportReport.label(d));
            button(row, "Connect (" + CONNECT_LIMIT_MS / 1000 + " s)", v -> rfcomm(d, false));
            button(row, "Wait for it (" + WAIT_LIMIT_MS / 1000 + " s)", v -> rfcomm(d, true));
            button(row, "iAP2 handshake: connect", v -> handshake(d, false));
            button(row, "iAP2 handshake: wait", v -> handshake(d, true));
        }
        File[] earlier = reportDir().listFiles();
        status.setText((earlier == null ? 0 : earlier.length) + " earlier report(s) saved in " + reportDir()
                + "; they are separate files and are not part of what is shown here.");
        setBusy(current != null);
        render();
    }

    private void render() {
        report.setText(text());
    }

    private String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenAuto transport check, debug build\n");
        sb.append("Captured ").append(capturedAt).append(" on this device. Sections marked \"observed\" were read from Android at that moment;\n");
        sb.append("build settings are listed apart. Earlier saved reports are separate files, not merged here.\n");
        sb.append("Contains Bluetooth addresses and device names.\n\n");
        for (String l : snapshot) sb.append(l).append('\n');
        sb.append("\n== checks started on this screen, each with its own time\n");
        if (checks.isEmpty()) sb.append("none yet\n");
        for (String l : checks) sb.append(l).append('\n');
        return sb.toString();
    }

    private void check(String text) {
        checks.add(text);
        for (String l : text.split("\n")) Log.i(TAG, l);
        render();
    }

    // ---- USB: access for the selected device, then its raw descriptors -----------------------------

    private void readUsb(final UsbDevice d) {
        final UsbManager usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (!usb.hasPermission(d)) {
            usbAsked = d.getDeviceName();
            Intent i = new Intent(ACTION_USB_PERMISSION);
            i.setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            status.setText("Asked Android for access to USB " + TransportReport.id(d) + "; answer its dialog.");
            return;
        }
        final String title = now() + " USB " + TransportReport.id(d) + " " + d.getDeviceName()
                + ": raw descriptors (opened only to read them; no configuration set, no interface claimed, no transfer sent)";
        submit("usb " + d.getDeviceName(), "Reading the descriptors...", c -> title + "\n" + descriptors(usb, d));
    }

    private static String descriptors(UsbManager usb, UsbDevice d) {
        UsbDeviceConnection c = usb.openDevice(d);
        if (c == null) return "  openDevice returned nothing (device gone or access withdrawn); nothing read";
        try {
            byte[] raw = c.getRawDescriptors();
            if (raw == null) return "  getRawDescriptors returned nothing";
            StringBuilder sb = new StringBuilder("  " + raw.length + " bytes\n");
            for (String l : UsbDescriptors.describe(raw)) sb.append("  ").append(l).append('\n');
            for (int i = 0; i < raw.length; i += 32) sb.append("  ").append(UsbDescriptors.hex(raw, i, Math.min(32, raw.length - i))).append('\n');
            return sb.substring(0, sb.length() - 1);
        } finally {
            c.close();
        }
    }

    private final BroadcastReceiver usbAnswer = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d == null || !d.getDeviceName().equals(usbAsked)) return;
            usbAsked = null;
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                readUsb(d);
            } else {
                check(now() + " USB " + TransportReport.id(d) + " " + d.getDeviceName() + ": access denied in Android's dialog, nothing read");
                refresh();
            }
        }
    };

    // ---- Bluetooth: one RFCOMM check with the selected paired phone ---------------------------------

    private void rfcomm(BluetoothDevice d, boolean wait) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        final long limit = wait ? WAIT_LIMIT_MS : CONNECT_LIMIT_MS;
        final RfcommCheck check = new RfcommCheck(wait ? accept(a, d) : connect(a, d), BluetoothProfile.DETECT);
        final String title = now() + " RFCOMM " + (wait ? "wait for " : "connect to ") + TransportReport.label(d) + ", limit " + limit / 1000
                + " s, service " + (wait ? "(this radio's) " + TransportReport.ACCESSORY_SERVICE : "(the phone's) " + TransportReport.IPHONE_SERVICE)
                + "; the UUID and the detection bytes are hypotheses until a phone answers";
        cancelCheck = check::cancel;
        submit("rfcomm " + d.getAddress(), wait ? "Waiting for the phone to connect; Cancel stops it." : "Connecting; Cancel stops it.", c -> {
            StringBuilder sb = new StringBuilder(title);
            for (String l : check.run(limit)) sb.append("\n  ").append(l);
            return sb.toString();
        });
    }

    private void cancelRfcomm() {
        if (cancelCheck == null) return;
        cancelCheck.run();
        status.setText(now() + " Cancel: sockets closed, waiting for the blocked call to return. If it never does, that is an observation too.");
        Log.i(TAG, "cancel pressed, sockets closed");
    }

    /**
     * The Bluetooth profile's link and control session with the selected phone, over the same two
     * endpoints, with the SYNTHETIC identity the MFi probe left in files/ (run that probe once
     * first). This radio's identification, the synthetic certificate and a signature go to the
     * phone; an iPhone is expected to refuse the certificate, and everything before that verdict
     * is what the report is for.
     */
    private void handshake(final BluetoothDevice d, final boolean wait) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) return;
        final File identityDir = SyntheticMfiProbe.identityDir(getFilesDir());
        final String address = adapterAddress(a), firmware = versionName();
        final String title = now() + " iAP2 handshake with the SYNTHETIC identity in " + identityDir + ", " + (wait ? "waiting for " : "connecting to ")
                + TransportReport.label(d) + ", limit " + HANDSHAKE_LIMIT_MS / 1000 + " s, service "
                + (wait ? "(this radio's) " + TransportReport.ACCESSORY_SERVICE : "(the phone's) " + TransportReport.IPHONE_SERVICE)
                + "; the UUID, the detection bytes, the link proposal and the identification fields are hypotheses until a phone answers";
        final RfcommCheck.Endpoint endpoint = wait ? accept(a, d) : connect(a, d);
        final AtomicReference<HandshakeCheck> check = new AtomicReference<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        cancelCheck = () -> {
            cancelled.set(true);
            HandshakeCheck c = check.get();
            if (c != null) c.cancel();
            else endpoint.close();
        };
        submit("handshake " + d.getAddress(), (wait ? "Waiting for the phone to connect" : "Connecting") + ", then the iAP2 handshake; Cancel stops it.", c -> {
            StringBuilder sb = new StringBuilder(title);
            LocalMfiAuthenticator auth;
            try {
                auth = LocalMfiAuthenticator.load(identityDir);
            } catch (LocalMfiAuthenticator.IdentityException e) {
                return sb.append("\n  not started: no usable SYNTHETIC identity (").append(e.getMessage())
                        .append("); run the Synthetic MFi probe once, then try again").toString();
            }
            BluetoothProfile.Identification id;
            try {
                id = new BluetoothProfile.Identification(BluetoothProfile.Identification.DEFAULT_NAME, "OA-DEBUG", "OpenAuto", "SYNTHETIC-0001",
                        firmware, Build.MODEL, addressBytes(address));
            } catch (IllegalArgumentException e) {
                return sb.append("\n  not started: ").append(e.getMessage()).toString();
            }
            sb.append("\n  identification: Name \"").append(BluetoothProfile.Identification.DEFAULT_NAME)
                    .append("\", ModelIdentifier OA-DEBUG, Manufacturer OpenAuto, SerialNumber SYNTHETIC-0001, FirmwareVersion ")
                    .append(firmware).append(", HardwareVersion ").append(Build.MODEL).append(", Bluetooth address ").append(address);
            HandshakeCheck h = new HandshakeCheck(endpoint, auth, id);
            check.set(h);
            if (cancelled.get()) h.cancel();
            for (String l : h.run(HANDSHAKE_LIMIT_MS)) sb.append("\n  ").append(l);
            return sb.toString();
        });
    }

    /** The adapter address as Android hands it out (a placeholder from Android 6 on), or null when withheld. */
    @SuppressLint({"MissingPermission", "HardwareIds"})
    private static String adapterAddress(BluetoothAdapter a) {
        try {
            return a.getAddress();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** "AA:BB:CC:DD:EE:FF" as 6 bytes; anything else is refused with the reason, like the profile's own checks. */
    static byte[] addressBytes(String address) {
        if (address == null) throw new IllegalArgumentException("Bluetooth address withheld by Android; it is mandatory for this transport");
        String[] parts = address.split(":");
        if (parts.length != 6) throw new IllegalArgumentException("Bluetooth address \"" + address + "\" is not six colon-separated bytes");
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) {
            if (parts[i].length() != 2) throw new IllegalArgumentException("Bluetooth address \"" + address + "\" is not six colon-separated bytes");
            try {
                b[i] = (byte) Integer.parseInt(parts[i], 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bluetooth address \"" + address + "\" is not hexadecimal");
            }
        }
        return b;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    // The endpoints are used only after the paired devices could be listed, so with the permission;
    // should it be withdrawn meanwhile, RfcommCheck reports the SecurityException as an observation.
    // cancelDiscovery is called below API 31 only, where BLUETOOTH_ADMIN (declared) is what it needs.

    private static RfcommCheck.Endpoint connect(final BluetoothAdapter adapter, final BluetoothDevice device) {
        return new Socket() {
            @Override
            @SuppressLint("MissingPermission")
            public String open() throws IOException {
                String discovery;
                if (Build.VERSION.SDK_INT < 31) {
                    adapter.cancelDiscovery();
                    discovery = "discovery cancelled first";
                } else {
                    discovery = "discovery not cancelled: the app has no BLUETOOTH_SCAN";
                }
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(UUID.fromString(TransportReport.IPHONE_SERVICE));
                use(s);
                s.connect();
                return "to " + device.getAddress() + ", " + discovery;
            }
        };
    }

    private static RfcommCheck.Endpoint accept(final BluetoothAdapter adapter, final BluetoothDevice device) {
        return new Socket() {
            private volatile BluetoothServerSocket server;

            @Override
            @SuppressLint("MissingPermission")
            public String open() throws IOException {
                BluetoothServerSocket ss = adapter.listenUsingRfcommWithServiceRecord("OpenAuto iAP2 check", UUID.fromString(TransportReport.ACCESSORY_SERVICE));
                server = ss;
                if (closed) ss.close();
                try {
                    BluetoothSocket s = ss.accept();
                    use(s);
                    String from = s.getRemoteDevice().getAddress();
                    if (!from.equalsIgnoreCase(device.getAddress())) {
                        throw new IOException("a different device, " + from + ", connected; closed without sending anything");
                    }
                    return "from " + from;
                } finally {
                    ss.close();
                }
            }

            @Override
            public void close() {
                super.close();
                BluetoothServerSocket ss = server;
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        };
    }

    /** Holds the connected socket; {@link #close} from any thread closes it, before or after it exists. */
    private abstract static class Socket implements RfcommCheck.Endpoint {
        volatile boolean closed;
        private volatile BluetoothSocket socket;

        void use(BluetoothSocket s) throws IOException {
            socket = s;
            if (closed) {
                s.close();
                throw new IOException("closed before the socket was used");
            }
        }

        @Override
        public InputStream input() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() {
            closed = true;
            BluetoothSocket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---- report file ---------------------------------------------------------------------------------

    private void save() {
        File dir = reportDir();
        File file = new File(dir, "transport-check-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
        String text = text();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
                w.write(text);
            }
            status.setText("Saved " + file + ". Earlier reports in that folder are separate files.");
            Log.i(TAG, "report saved to " + file);
        } catch (IOException e) {
            status.setText("Not saved: " + e + ". The report is in logcat (tag " + TAG + ").");
        }
        for (String l : text.split("\n")) Log.i(TAG, l);
    }

    /** External app storage when Android offers it (API 16-18 need the debug-only storage permission), else internal. */
    private File reportDir() {
        File base = getExternalFilesDir(null);
        return new File(base != null ? base : getFilesDir(), "transport-check");
    }

    // ---- plumbing ------------------------------------------------------------------------------------

    private void submit(String name, String running, ProbeRunner.Operation op) {
        setBusy(true);
        status.setText(running);
        current = runner().submit(name, op, text -> {
            current = null;
            cancelCheck = null;
            setBusy(false);
            status.setText("");
            check(text);
        });
    }

    private LinearLayout row(String label) {
        LinearLayout row = new LinearLayout(this);
        TextView t = new TextView(this);
        t.setText(label);
        row.addView(t);
        actions.addView(row);
        return row;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(onClick);
        parent.addView(b);
        return b;
    }

    /** While an operation runs only Cancel works, and only for an RFCOMM check. */
    private void setBusy(boolean busy) {
        for (int i = 0; i < top.getChildCount(); i++) top.getChildAt(i).setEnabled(!busy);
        cancel.setEnabled(busy && cancelCheck != null);
        for (int r = 0; r < actions.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) actions.getChildAt(r);
            for (int i = 1; i < row.getChildCount(); i++) row.getChildAt(i).setEnabled(!busy);
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
    }

    /** One per process, created on the main thread. */
    private static ProbeRunner runner() {
        if (runner == null) {
            Handler main = new Handler(Looper.getMainLooper());
            runner = new ProbeRunner(Executors.newSingleThreadExecutor(r -> new Thread(r, "transport-check")), main::post,
                    line -> Log.i(TAG, line));
        }
        return runner;
    }
}
