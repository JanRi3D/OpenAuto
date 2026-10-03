package me.ri3d.openauto;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ri3d.openauto.aa.NativeTls;
import me.ri3d.openauto.aa.Session;
import me.ri3d.openauto.aa.TlsCredentials;
import me.ri3d.openauto.aa.VideoGeometry;
import me.ri3d.openauto.aa.Wire;
import me.ri3d.openauto.diag.DeviceInfo;
import me.ri3d.openauto.media.AndroidMedia;
import me.ri3d.openauto.settings.Prefs;
import me.ri3d.openauto.transport.Aoa;
import me.ri3d.openauto.transport.TcpTransport;
import me.ri3d.openauto.transport.Transport;
import me.ri3d.openauto.transport.UsbKinds;
import me.ri3d.openauto.transport.UsbTransport;
import me.ri3d.openauto.wireless.Hotspot;
import me.ri3d.openauto.wireless.WirelessServer;

/**
 * App-wide owner of the single active connection attempt. Runs the USB accessory handshake, opens
 * transports, creates one {@link Session} per attempt and publishes its state on the main thread.
 * Callbacks from a session that is no longer the current one are ignored.
 */
public final class ConnectionManager implements Session.Listener {
    private static final String TAG = "Connection";
    private static final String ACTION_USB_PERMISSION = "me.ri3d.openauto.USB_PERMISSION";
    private static final int SWITCH_TIMEOUT_MS = 10000;

    public enum Phase { IDLE, USB_PERMISSION, USB_SWITCHING, HOTSPOT, WIRELESS_WAITING, CONNECTING, VERSION, HANDSHAKE, DISCOVERY, READY, PROJECTING, RECONNECTING, ERROR }

    public interface Listener {
        void onConnectionChanged(ConnectionManager m);
    }

    private static ConnectionManager instance;

    public static synchronized ConnectionManager get(Context ctx) {
        if (instance == null) instance = new ConnectionManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;
    private final UsbManager usb;
    private final List<Listener> listeners = new ArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();

    private TlsCredentials creds;
    private Session session;
    private AndroidMedia media;
    private Surface surface;
    private int surfaceCrop;
    private boolean nativeTlsTried;
    private Phase phase = Phase.IDLE;
    private String detail;
    private String lastError;
    private boolean usbMode;
    private boolean wirelessMode;
    private Hotspot hotspot;
    private WirelessServer wireless;
    private boolean userStopped;
    private int retries;
    private String manualHost;
    private int manualPort;
    private long switchStartedAt;
    private String permissionDevice;
    private boolean permissionAsked;
    private long permissionSince;
    private volatile boolean uiFocused = true;
    private Runnable switchTimeout, pollAccessory;

    // Everything the UI sends to the phone (touch, keys, focus, shutdown, night mode) goes through this
    // thread: a socket or USB write on the UI thread is a NetworkOnMainThreadException on modern Android.
    private static final int MSG_TOUCH = 1, MSG_BUTTON = 2;
    private final Handler sender;
    private volatile int areaW, areaH; // size of the projection stage in pixels, once known

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // flag passed on API 33+; older APIs have no flag parameter
    private ConnectionManager(Context app) {
        this.app = app;
        prefs = new Prefs(app);
        HandlerThread sendThread = new HandlerThread("aa-ui-send");
        sendThread.start();
        sender = new Handler(sendThread.getLooper(), msg -> {
            Session ses = session;
            if (ses == null || ses.isClosed() || !ses.input.isBound()) return true;
            if (msg.what == MSG_TOUCH) ses.input.touch(msg.arg1, msg.arg2 >>> 16, msg.arg2 & 0xFFFF);
            else if (msg.what == MSG_BUTTON) ses.input.button(msg.arg1, msg.arg2 != 0);
            return true;
        });
        usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            // System broadcasts and our own PendingIntent reach a non-exported receiver.
            app.registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(usbReceiver, f);
        }
    }

    // ---- observation ---------------------------------------------------------------------------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Phase phase() { return phase; }
    public String detail() { return detail; }
    public String lastError() { return lastError; }
    public Session session() { return session; }
    public AndroidMedia media() { return media; }
    public boolean isBusy() { return phase != Phase.IDLE && phase != Phase.ERROR; }
    public boolean isUsb() { return usbMode; }

    public synchronized List<String> logLines() {
        return new ArrayList<>(log);
    }

    private void setPhase(Phase p, String d) {
        boolean resumed = p == Phase.PROJECTING && phase != Phase.PROJECTING;
        phase = p;
        detail = d;
        if (p == Phase.ERROR) lastError = d;
        addLog(p + (d == null ? "" : ": " + d));
        for (Listener l : new ArrayList<>(listeners)) l.onConnectionChanged(this);
        if (resumed) bringProjectionToFront();
    }

    /** Video started (first time or after a reconnect): make sure the projection screen is showing. */
    private void bringProjectionToFront() {
        try {
            Intent i = new Intent(app, ProjectionActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
        } catch (RuntimeException e) {
            addLog("could not open the projection screen: " + e.getMessage()); // Android 10+ background-start limits
        }
    }

    synchronized void addLog(String line) {
        Log.i(TAG, line);
        log.addLast(timestamp() + " " + line);
        while (log.size() > 300) log.removeFirst();
    }

    private static String timestamp() {
        long ms = System.currentTimeMillis() % 86400000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    // ---- USB -----------------------------------------------------------------------------------

    /**
     * A phone we might switch to accessory mode; accessory devices first. Head units have internal USB
     * devices (radio tuners, hubs), so only devices that look like a phone count (see UsbKinds).
     */
    public UsbDevice findUsbCandidate() {
        if (usb == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (Aoa.isAccessory(d)) return d;
            if (fallback == null && UsbKinds.looksLikePhone(d)) fallback = d;
        }
        return fallback;
    }

    public boolean usbHostAvailable() {
        return usb != null && app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
    }

    /** Starts the wired flow with the given device, or with the first candidate found. */
    public void connectUsb(UsbDevice device) {
        if (isBusy()) return;
        userStopped = false;
        retries = 0;
        usbMode = true;
        if (device == null) device = findUsbCandidate();
        if (device == null) {
            setPhase(Phase.ERROR, app.getString(R.string.err_no_usb_device));
            return;
        }
        proceedUsb(device);
    }

    private void proceedUsb(UsbDevice device) {
        main.removeCallbacks(permissionPoll);
        if (!usb.hasPermission(device)) {
            awaitUsbPermission(device);
            return;
        }
        if (Aoa.isAccessory(device)) {
            openAccessory(device);
        } else {
            switchToAccessory(device);
        }
    }

    /**
     * Waits for USB permission by whichever route it arrives. A phone that re-enumerated in accessory
     * mode matches the manifest's device filter, so Android asks "open with this app?" by itself and
     * grants the permission with the answer. Our own request then only put a second dialog on screen,
     * and a real head unit sat here until the user cancelled, because the broadcast for our request never
     * came. So the permission itself is polled, and we ask only when no dialog of Android's has
     * taken the window focus from our screen.
     */
    private void awaitUsbPermission(UsbDevice device) {
        setPhase(Phase.USB_PERMISSION, device.getDeviceName());
        permissionDevice = device.getDeviceName();
        permissionAsked = false;
        permissionSince = System.currentTimeMillis();
        if (!Aoa.isAccessory(device)) requestUsbPermission(device); // nobody else asks for an ordinary device
        main.postDelayed(permissionPoll, 300);
    }

    private void requestUsbPermission(UsbDevice device) {
        permissionAsked = true;
        Intent i = new Intent(ACTION_USB_PERMISSION);
        i.setPackage(app.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(device, PendingIntent.getBroadcast(app, 0, i, flags));
    }

    private final Runnable permissionPoll = new Runnable() {
        @Override
        public void run() {
            if (phase != Phase.USB_PERMISSION) return;
            UsbDevice d = usb.getDeviceList().get(permissionDevice);
            if (d == null) {
                setPhase(Phase.ERROR, app.getString(R.string.err_usb_detached));
                return;
            }
            if (usb.hasPermission(d)) {
                proceedUsb(d);
                return;
            }
            if (!permissionAsked && uiFocused && System.currentTimeMillis() - permissionSince > 1500) requestUsbPermission(d);
            main.postDelayed(this, 300);
        }
    };

    /** Whether one of our screens has the window focus, i.e. no system dialog is in front of it. */
    public void setUiFocused(boolean focused) {
        uiFocused = focused;
    }

    private void openAccessory(UsbDevice device) {
        cancelSwitchWait();
        try {
            Transport t = UsbTransport.open(usb, device);
            startSession(t);
        } catch (IOException e) {
            setPhase(Phase.ERROR, e.getMessage());
        }
    }

    private void switchToAccessory(final UsbDevice device) {
        setPhase(Phase.USB_SWITCHING, device.getDeviceName());
        new Thread(() -> {
            String err = null;
            UsbDeviceConnection c = usb.openDevice(device);
            if (c == null) {
                err = "openDevice failed";
            } else {
                try {
                    int v = Aoa.protocolVersion(c);
                    addLog("AOA protocol " + v + " on " + device.getDeviceName());
                    Aoa.start(c);
                } catch (IOException e) {
                    err = e.getMessage();
                } finally {
                    c.close();
                }
            }
            final String error = err;
            main.post(() -> {
                if (phase != Phase.USB_SWITCHING) return;
                if (error != null) {
                    setPhase(Phase.ERROR, app.getString(R.string.err_aoa, error));
                    return;
                }
                // The phone now re-enumerates as 18D1:2D00/2D01; ATTACHED arrives via the receiver.
                // Poll as a fallback for devices that deliver the broadcast late or not at all.
                switchStartedAt = System.currentTimeMillis();
                pollAccessory = () -> {
                    if (phase != Phase.USB_SWITCHING) return;
                    UsbDevice acc = findUsbCandidate();
                    if (acc != null && Aoa.isAccessory(acc)) {
                        proceedUsb(acc);
                    } else if (System.currentTimeMillis() - switchStartedAt > SWITCH_TIMEOUT_MS) {
                        setPhase(Phase.ERROR, app.getString(R.string.err_aoa_timeout));
                    } else {
                        main.postDelayed(pollAccessory, 500);
                    }
                };
                main.postDelayed(pollAccessory, 700);
            });
        }, "aoa-switch").start();
    }

    private void cancelSwitchWait() {
        if (pollAccessory != null) main.removeCallbacks(pollAccessory);
        pollAccessory = null;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (phase != Phase.USB_PERMISSION) return;
                if (!granted || device == null) {
                    setPhase(Phase.ERROR, app.getString(R.string.err_usb_permission));
                } else {
                    proceedUsb(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) && device != null) {
                addLog("USB attached " + device.getDeviceName() + " " + Integer.toHexString(device.getVendorId()) + ":" + Integer.toHexString(device.getProductId()));
                if (phase == Phase.USB_SWITCHING && Aoa.isAccessory(device)) {
                    proceedUsb(device);
                } else if (phase == Phase.IDLE || phase == Phase.ERROR) {
                    for (Listener l : new ArrayList<>(listeners)) l.onConnectionChanged(ConnectionManager.this);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) && device != null) {
                addLog("USB detached " + device.getDeviceName());
                if (session != null && usbMode && Aoa.isAccessory(device)) {
                    session.close(app.getString(R.string.err_usb_detached));
                } else if (phase == Phase.IDLE || phase == Phase.ERROR) {
                    for (Listener l : new ArrayList<>(listeners)) l.onConnectionChanged(ConnectionManager.this);
                }
            }
        }
    };

    // ---- TCP -----------------------------------------------------------------------------------

    /** Manual Wi-Fi mode and Self Mode: connect to a phone whose head-unit server is listening. */
    public void connectTcp(final String host, final int port) {
        if (isBusy()) return;
        userStopped = false;
        retries = 0;
        usbMode = false;
        manualHost = host;
        manualPort = port;
        setPhase(Phase.CONNECTING, host + ":" + port);
        new Thread(() -> {
            try {
                final Transport t = TcpTransport.connect(host, port, 8000);
                main.post(() -> {
                    if (phase != Phase.CONNECTING) {
                        try { t.close(); } catch (IOException ignored) { }
                        return;
                    }
                    startSession(t);
                });
            } catch (final IOException e) {
                main.post(() -> {
                    if (phase == Phase.CONNECTING) setPhase(Phase.ERROR, app.getString(R.string.err_tcp, host, port, e.getMessage()));
                });
            }
        }, "tcp-connect").start();
    }

    // ---- automatic wireless ------------------------------------------------------------------------

    /** Bluetooth handshake + hotspot + TCP 5288 (protocol-reference §11). Needs a paired phone chosen in Settings. */
    @SuppressLint("MissingPermission") // Perms.hasBluetooth() checked first
    public void startWireless() {
        if (isBusy()) return;
        userStopped = false;
        retries = 0;
        usbMode = false;
        wirelessMode = true;
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) { setPhase(Phase.ERROR, app.getString(R.string.err_bt_none)); return; }
        if (!Perms.hasBluetooth(app)) { setPhase(Phase.ERROR, app.getString(R.string.bt_permission)); return; }
        if (!Hotspot.supported(app)) { setPhase(Phase.ERROR, app.getString(R.string.err_hotspot_unsupported)); return; }
        if (!Perms.hasHotspot(app)) { setPhase(Phase.ERROR, app.getString(R.string.err_location_permission)); return; }
        if (!bt.isEnabled()) {
            bt.enable();
            long until = System.currentTimeMillis() + 6000;
            while (!bt.isEnabled() && System.currentTimeMillis() < until) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            }
            if (!bt.isEnabled()) { setPhase(Phase.ERROR, app.getString(R.string.err_bt_off)); return; }
        }
        final String phoneMac = prefs.btPhone();
        if (prefs.apSsid() == null) {
            SecureRandom rnd = new SecureRandom();
            prefs.put(Prefs.AP_SSID, "OPENAUTO-" + Integer.toHexString(0x1000 + rnd.nextInt(0xEFFF)).toUpperCase(Locale.ROOT));
            prefs.put(Prefs.AP_PASS, Long.toString(Math.abs(rnd.nextLong()), 36).substring(0, 10));
        }
        setPhase(Phase.HOTSPOT, null);
        hotspot = new Hotspot(app);
        final BluetoothAdapter adapter = bt;
        hotspot.start(prefs.apSsid(), prefs.apPass(), new Hotspot.Callback() {
            @Override
            public void onStarted(String ssid, String passphrase, String bssid, String ip) {
                if (phase != Phase.HOTSPOT) return;
                wireless = new WirelessServer(new WirelessServer.Listener() {
                    @Override public void onLog(String line) { addLog(line); }
                    @Override public void onPhone(final Socket socket) {
                        main.post(() -> {
                            if (phase != Phase.WIRELESS_WAITING) { try { socket.close(); } catch (IOException ignored) { } return; }
                            try {
                                startSession(new TcpTransport(socket, "Wi-Fi " + socket.getInetAddress().getHostAddress()));
                            } catch (IOException e) {
                                setPhase(Phase.ERROR, e.getMessage());
                            }
                        });
                    }
                    @Override public void onFailed(final String reason) {
                        main.post(() -> { if (phase == Phase.WIRELESS_WAITING) setPhase(Phase.ERROR, reason); });
                    }
                });
                try {
                    lastWifiInfo = new WirelessServer.WifiInfo(ssid, passphrase, bssid, ip,
                            WirelessServer.TCP_PORT);
                    wireless.start(adapter, lastWifiInfo, phoneMac);
                    setPhase(Phase.WIRELESS_WAITING, ssid);
                } catch (IOException e) {
                    setPhase(Phase.ERROR, e.getMessage());
                    stopWireless();
                }
            }

            @Override
            public void onFailed(String reason) {
                if (phase == Phase.HOTSPOT) setPhase(Phase.ERROR, reason);
                stopWireless();
            }
        });
    }

    private void stopWireless() {
        if (wireless != null) { wireless.stop(); wireless = null; }
        if (hotspot != null) { hotspot.stop(); hotspot = null; }
    }

    public boolean wirelessCapable() {
        return DeviceInfo.hasBluetooth() && Hotspot.supported(app);
    }

    // ---- session ------------------------------------------------------------------------------

    private synchronized TlsCredentials credentials() throws IOException {
        if (creds == null) {
            creds = TlsCredentials.load(app.getAssets().open("tls/headunit-cert.pem"), app.getAssets().open("tls/headunit-key.pk8.pem"));
        }
        return creds;
    }

    /**
     * Tries the device's own OpenSSL once per process; not for the UI thread. A flag on disk survives a
     * native crash inside a vendor's library, so a device that cannot do this is not tried again
     * (Settings › General › Reset clears the flag).
     */
    public synchronized void prepareNativeTls() {
        if (nativeTlsTried) return;
        nativeTlsTried = true;
        if (Build.VERSION.SDK_INT > NativeTls.MAX_SDK) {
            NativeTls.init(Build.VERSION.SDK_INT, null); // only records why it is not used
            return;
        }
        SharedPreferences sp = prefs.raw();
        if (sp.getBoolean(Prefs.NATIVE_TLS_PROBING, false)) {
            NativeTls.disable("its self-test did not finish last time");
        } else {
            try {
                sp.edit().putBoolean(Prefs.NATIVE_TLS_PROBING, true).commit();
                NativeTls.init(Build.VERSION.SDK_INT, credentials());
                sp.edit().putBoolean(Prefs.NATIVE_TLS_PROBING, false).commit();
            } catch (IOException e) {
                NativeTls.disable(e.getMessage());
            }
        }
        addLog("tls: system OpenSSL " + (NativeTls.usable() ? "in use: " : "not used: ") + NativeTls.status());
    }

    /** Throughput of the system-OpenSSL engine in MB/s for Diagnostics, or -1 when it is not in use. */
    public double nativeTlsSpeed() {
        prepareNativeTls();
        if (!NativeTls.usable()) return -1;
        try {
            return NativeTls.selfTest(credentials(), 400);
        } catch (IOException e) {
            return -1;
        }
    }

    private void startSession(Transport t) {
        try {
            credentials();
        } catch (Exception e) {
            try { t.close(); } catch (IOException ignored) { }
            setPhase(Phase.ERROR, app.getString(R.string.err_credentials, e.getMessage()));
            return;
        }
        media = new AndroidMedia(app);
        media.video.preferSoftware = "sw".equals(prefs.decoder());
        media.video.setSurface(surface, surfaceCrop);
        final Session.Config cfg = new Session.Config();
        applyVideo(cfg);
        final AndroidMedia m = media;
        m.video.setMargins(cfg.marginWidth, cfg.marginHeight);
        cfg.beforeDiscovery = () -> { // the projection stage may have been measured since
            applyVideo(cfg);
            m.video.setMargins(cfg.marginWidth, cfg.marginHeight);
        };
        cfg.prepareTls = this::prepareNativeTls;
        cfg.fpsEnum = prefs.fps() == 60 ? Wire.FPS_60 : Wire.FPS_30;
        cfg.dpi = prefs.dpi();
        cfg.media = prefs.audioMedia();
        cfg.speech = prefs.audioSpeech();
        // Always declare the microphone: a real phone closes the connection right after discovery when the
        // head unit has no audio input channel. Whether capture actually starts is decided when it asks.
        cfg.mic = true;
        media.mic.enabled = prefs.mic();
        cfg.night = isNight();
        cfg.nightSource = this::isNight;
        cfg.carSerial = Build.SERIAL == null ? "OPENAUTO" : Build.SERIAL;
        cfg.model = Build.MODEL;
        cfg.manufacturer = Build.MANUFACTURER;
        try { cfg.swVersion = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        session = new Session(t, creds, cfg, media, this);
        ProjectionService.start(app);
        setPhase(Phase.CONNECTING, t.describe());
        session.start();
    }

    /** Resolution from the settings and margins that give the content area the shape of the projection stage. */
    private void applyVideo(Session.Config cfg) {
        cfg.setResolution(prefs.res());
        int w = areaW, h = areaH;
        if (w <= 0 || h <= 0) {
            w = prefs.areaW();
            h = prefs.areaH();
        }
        if (w <= 0 || h <= 0) { // never measured: assume the whole display, landscape
            DisplayMetrics m = app.getResources().getDisplayMetrics();
            w = Math.max(m.widthPixels, m.heightPixels);
            h = Math.min(m.widthPixels, m.heightPixels);
        }
        // "Bars": no margins, the whole frame is shown with bars beside it instead of being cropped.
        int[] margins = "bars".equals(prefs.fit()) ? new int[]{0, 0} : VideoGeometry.margins(cfg.videoWidth, cfg.videoHeight, w, h);
        cfg.marginWidth = margins[0];
        cfg.marginHeight = margins[1];
    }

    /** The video config a session started now would use; lets the projection screen lay out before connecting. */
    public Session.Config previewConfig() {
        Session.Config cfg = new Session.Config();
        applyVideo(cfg);
        return cfg;
    }

    /** Reported by the projection screen whenever its stage is measured. */
    public void setProjectionArea(int w, int h) {
        if (w == areaW && h == areaH) return;
        areaW = w;
        areaH = h;
        prefs.put(Prefs.AREA_W, w);
        prefs.put(Prefs.AREA_H, h);
    }

    public boolean isNight() {
        String mode = prefs.night();
        if ("day".equals(mode)) return false;
        if ("night".equals(mode)) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 20 || h < 7;
    }

    /** Called from the Settings screen when the night mode choice changes. */
    public void nightModeChanged() {
        final Session ses = session;
        if (ses != null && !ses.isClosed()) sender.post(() -> ses.sensor.setNight(isNight()));
    }

    @Override
    public void onState(final Session s, final Session.State state, final String d) {
        main.post(() -> {
            if (s != session) return; // stale attempt
            switch (state) {
                case VERSION: setPhase(Phase.VERSION, null); break;
                case HANDSHAKE: setPhase(Phase.HANDSHAKE, null); break;
                case DISCOVERY: setPhase(Phase.DISCOVERY, null); break;
                case READY: setPhase(Phase.READY, d); break;
                case PROJECTING: setPhase(Phase.PROJECTING, s.phoneLabel()); break;
                case CLOSED: onClosed(s, d); break;
                default: break;
            }
        });
    }

    private void onClosed(Session s, String reason) {
        session = null;
        if (media != null) {
            media.release();
            media = null;
        }
        ProjectionService.stop(app);
        boolean wasProjecting = phase == Phase.PROJECTING || phase == Phase.READY;
        if (userStopped) {
            stopWireless();
            setPhase(Phase.IDLE, reason);
            return;
        }
        if (wirelessMode && prefs.autoReconnect() && wireless != null && wasProjecting) {
            // Keep the hotspot and servers up: the phone reconnects over Wi-Fi by itself.
            addLog("wireless: session ended (" + reason + "), waiting for the phone again");
            try {
                wireless.start(BluetoothAdapter.getDefaultAdapter(), lastWifiInfo(), prefs.btPhone());
                setPhase(Phase.WIRELESS_WAITING, reason);
                return;
            } catch (IOException | RuntimeException e) {
                addLog("wireless: restart failed: " + e);
            }
        }
        stopWireless();
        if (prefs.autoReconnect() && retries < 3 && wasProjecting && !wirelessMode) {
            retries++;
            final int attempt = retries;
            long delay = 2000L * attempt;
            setPhase(Phase.RECONNECTING, reason);
            addLog("reconnect attempt " + attempt + " in " + delay + " ms");
            main.postDelayed(() -> {
                if (phase != Phase.RECONNECTING || userStopped) return;
                if (usbMode) {
                    UsbDevice dev = findUsbCandidate();
                    if (dev == null) { setPhase(Phase.ERROR, reason); return; }
                    phase = Phase.IDLE;
                    connectUsb(dev);
                } else if (manualHost != null) {
                    phase = Phase.IDLE;
                    connectTcp(manualHost, manualPort);
                }
                retries = attempt;
            }, delay);
            return;
        }
        setPhase(Phase.ERROR, reason);
    }

    @Override
    public void onLog(Session s, String line) {
        addLog(line);
    }

    private Runnable leaveProjection;

    /** The projection screen registers how to close itself when the phone asks for native focus (its Exit entry). */
    public void setLeaveProjectionHandler(Runnable r) {
        leaveProjection = r;
    }

    @Override
    public void onVideoFocusRequest(final Session s, final boolean projected) {
        main.post(() -> {
            if (s != session) return;
            if (!projected && leaveProjection != null) leaveProjection.run(); // session stays up; the launcher tile returns
            else if (projected) bringProjectionToFront();
        });
    }

    /** True while a session is up (connected or projecting), also when its screen is not showing. */
    public boolean isConnected() {
        return phase == Phase.READY || phase == Phase.PROJECTING;
    }

    /** User-initiated stop: polite shutdown request, then close. */
    public void stop() {
        userStopped = true;
        cancelSwitchWait();
        final Session ses = session;
        if (ses != null) {
            sender.post(() -> ses.requestShutdown(app.getString(R.string.stopped_by_user)));
        } else if (phase != Phase.IDLE) {
            stopWireless();
            setPhase(Phase.IDLE, null);
        }
    }

    private WirelessServer.WifiInfo lastWifiInfo;

    private WirelessServer.WifiInfo lastWifiInfo() {
        return lastWifiInfo;
    }

    public boolean isWireless() {
        return wirelessMode;
    }

    public void clearError() {
        if (phase == Phase.ERROR) setPhase(Phase.IDLE, null);
    }

    // ---- projection surface and input -------------------------------------------------------------

    /**
     * The projection Surface (null when it is destroyed). Tells the phone to stop/resume video.
     *
     * @param decoderCrop a VideoDecoder.CROP_ value: how the decoder removes the video margins when the
     *                    Surface has the content area's shape; CROP_NONE when the view does the fitting
     */
    public void setSurface(Surface s, int decoderCrop) {
        if (s == null && surface == null) return; // already withdrawn
        surface = s;
        surfaceCrop = decoderCrop;
        if (media != null) media.video.setSurface(s, decoderCrop);
        final Session ses = session;
        final boolean focused = s != null;
        if (ses != null && (ses.state() == Session.State.READY || ses.state() == Session.State.PROJECTING)) {
            sender.post(() -> {
                try {
                    ses.video.setFocus(focused);
                } catch (IOException e) {
                    addLog("focus change failed: " + e.getMessage());
                }
            });
        }
    }

    /** Touch in content coordinates (see VideoGeometry); queued to the sender thread, no allocation per event. */
    public void touch(int action, int x, int y) {
        sender.obtainMessage(MSG_TOUCH, action, (x << 16) | (y & 0xFFFF)).sendToTarget();
    }

    public void button(int code, boolean pressed) {
        sender.obtainMessage(MSG_BUTTON, code, pressed ? 1 : 0).sendToTarget();
    }

    public Prefs prefs() {
        return prefs;
    }

    /** Memory pressure from the Application: logged with the current media counters for later diagnosis. */
    public void noteMemoryPressure(int level) {
        Runtime rt = Runtime.getRuntime();
        String media = this.media == null ? "" : ", " + this.media.video.stats();
        addLog("memory pressure level " + level + ": java heap " + (rt.totalMemory() - rt.freeMemory()) / 1024 + " KiB used of "
                + rt.maxMemory() / 1024 + " KiB max, native " + Debug.getNativeHeapAllocatedSize() / 1024 + " KiB" + media);
    }

    /** Developer aid: every thread's stack to logcat (tag Connection), for stalls that leave no exception. */
    public static void dumpThreads() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            StringBuilder sb = new StringBuilder("thread \"").append(e.getKey().getName()).append("\" ").append(e.getKey().getState());
            for (StackTraceElement el : e.getValue()) sb.append("\n    at ").append(el);
            Log.i(TAG, sb.toString());
        }
    }
}
