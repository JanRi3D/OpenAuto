package me.ri3d.openauto.wireless;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/**
 * Automatic wireless connection (protocol-reference §11), head-unit side:
 * <ol>
 *   <li>an RFCOMM server with the Android Auto wireless UUID (the phone connects to it);</li>
 *   <li>over that link: WifiStartRequest{ip, port} → WifiInfoRequest ← → WifiInfoResponse{ssid, key, ...};</li>
 *   <li>a TCP server on port 5288 that the phone connects to once it has joined our hotspot.</li>
 * </ol>
 * The open-source dongles additionally present a Headset profile and connect to the phone's HSP
 * gateway to make the phone start this flow; an app cannot register HSP with public APIs. As a
 * best-effort trigger this server also tries outgoing RFCOMM connections with the same UUID, to the
 * phone chosen in Settings or to every paired phone, and runs the same exchange; like the dongle's
 * trigger it retries every 20 s until the phone reaches TCP. Whether a phone accepts either path from
 * a non-HSP device is UNVERIFIED and must be tested with real hardware.
 */
public final class WirelessServer {
    private static final String TAG = "Wireless";
    public static final UUID AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    public static final int TCP_PORT = 5288;
    /** Pause between rounds of the trigger; WirelessAndroidAutoDongle retries every 20 s (protocol-reference §11.1). */
    static final long RETRY_MS = 20000;

    // RFCOMM message ids (§11.4)
    static final int WIFI_START_REQUEST = 1, WIFI_INFO_REQUEST = 2, WIFI_INFO_RESPONSE = 3,
            WIFI_VERSION_REQUEST = 4, WIFI_VERSION_RESPONSE = 5, WIFI_CONNECT_STATUS = 6, WIFI_START_RESPONSE = 7;
    static final int SECURITY_WPA2_PERSONAL = 8, ACCESS_POINT_DYNAMIC = 1;

    public interface Listener {
        void onLog(String line);
        /** The phone connected over TCP: hand the socket to a Session. */
        void onPhone(Socket socket);
        void onFailed(String reason);
    }

    public static final class WifiInfo {
        public final String ssid, key, bssid, ip;
        public final int port;

        public WifiInfo(String ssid, String key, String bssid, String ip, int port) {
            this.ssid = ssid;
            this.key = key;
            this.bssid = bssid == null ? "" : bssid;
            this.ip = ip;
            this.port = port;
        }
    }

    private final Listener listener;
    private BluetoothServerSocket rfcomm;
    private ServerSocket tcp;
    private AtomicBoolean live; // the current start(); cleared by stop(), and once the phone is on Wi-Fi (no more triggers)
    private final List<Thread> threads = new ArrayList<>();

    public WirelessServer(Listener listener) {
        this.listener = listener;
    }

    /**
     * Opens both servers and starts the trigger. {@code phoneMac} is the phone chosen in Settings, or null
     * for any paired phone: then every bonded device that reports itself as a phone is tried in turn.
     */
    @SuppressLint("MissingPermission") // caller checks Perms.hasBluetooth()
    public synchronized void start(final BluetoothAdapter adapter, final WifiInfo info, final String phoneMac) throws IOException {
        stop();
        // Each thread keeps the flag and sockets of its own start(), so none of them outlives a restart.
        final AtomicBoolean live = this.live = new AtomicBoolean(true);
        final ServerSocket tcp = this.tcp = new ServerSocket(info.port, 2, InetAddress.getByName("0.0.0.0"));
        final BluetoothServerSocket rfcomm = this.rfcomm = adapter.listenUsingRfcommWithServiceRecord("AA Wireless", AA_UUID);
        listener.onLog("wireless: RFCOMM server up, TCP " + info.ip + ":" + info.port + ", hotspot " + info.ssid
                + ", trigger " + (phoneMac == null ? "any paired phone" : phoneMac));

        spawn("wireless-tcp", () -> {
            try {
                Socket s = tcp.accept();
                live.set(false); // the phone is on Wi-Fi: the trigger stops, as the dongle's does
                listener.onLog("wireless: phone connected over Wi-Fi from " + s.getInetAddress().getHostAddress());
                listener.onPhone(s);
            } catch (IOException e) {
                if (live.get()) listener.onFailed("TCP accept failed: " + e.getMessage());
            }
        });

        spawn("wireless-rfcomm", () -> {
            for (;;) {
                BluetoothSocket s;
                try {
                    s = rfcomm.accept();
                } catch (IOException e) {
                    // Closed by stop(), or Bluetooth turned off or its stack restarted: accepting again
                    // would fail at once, forever.
                    if (live.get()) listener.onFailed("Bluetooth server stopped: " + e.getMessage());
                    return;
                }
                try {
                    listener.onLog("wireless: phone connected over Bluetooth: " + s.getRemoteDevice().getAddress());
                    exchange(s.getInputStream(), s.getOutputStream(), info, listener::onLog);
                } catch (IOException e) {
                    listener.onLog("wireless: RFCOMM " + e.getMessage());
                } finally {
                    try { s.close(); } catch (IOException ignored) { }
                }
            }
        });

        spawn("wireless-trigger", () -> {
            try {
                Thread.sleep(1500);
                nudge(new Phones() {
                    @Override public List<String> targets() { return WirelessServer.targets(phoneMac, bonded(adapter)); }
                    @Override public void reach(String mac) throws IOException { reachPhone(adapter, mac, info); }
                }, live, RETRY_MS, listener::onLog);
            } catch (InterruptedException ignored) { // stop()
            }
        });
    }

    @SuppressLint("MissingPermission") // caller checks Perms.hasBluetooth()
    private void reachPhone(BluetoothAdapter adapter, String mac, WifiInfo info) throws IOException {
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) { // needs BLUETOOTH_SCAN from API 31; we never discover
        }
        BluetoothSocket s = adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(AA_UUID);
        try {
            s.connect();
            listener.onLog("wireless: outgoing RFCOMM to " + mac + " connected");
            exchange(s.getInputStream(), s.getOutputStream(), info, listener::onLog);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Paired devices as address to Bluetooth major device class (-1 when the class is unknown). */
    @SuppressLint("MissingPermission") // caller checks Perms.hasBluetooth()
    private static Map<String, Integer> bonded(BluetoothAdapter adapter) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        if (devices == null) return out; // Bluetooth turned off meanwhile
        for (BluetoothDevice d : devices) {
            BluetoothClass c = d.getBluetoothClass();
            out.put(d.getAddress(), c == null ? -1 : c.getMajorDeviceClass());
        }
        return out;
    }

    private void spawn(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    public synchronized void stop() {
        if (live != null) live.set(false);
        if (rfcomm != null) {
            try { rfcomm.close(); } catch (IOException ignored) { }
            rfcomm = null;
        }
        if (tcp != null) {
            try { tcp.close(); } catch (IOException ignored) { }
            tcp = null;
        }
        for (Thread t : threads) t.interrupt();
        threads.clear();
    }

    // ---- the trigger, without Bluetooth so it is unit-testable -----------------------------------

    /** The phones the trigger reaches out to; Bluetooth in {@link #start}, scripted in tests. */
    interface Phones {
        /** Addresses to try this round, in order. */
        List<String> targets();

        /** Connects to one phone and runs {@link #exchange}; throws when it cannot. */
        void reach(String mac) throws IOException;
    }

    /**
     * The phone chosen in Settings, or every paired phone ("any paired phone", {@code chosen} null). A
     * phone that does not report the phone device class can still be chosen by hand.
     */
    static List<String> targets(String chosen, Map<String, Integer> bonded) {
        if (chosen != null) return Collections.singletonList(chosen);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : bonded.entrySet()) {
            if (e.getValue() == BluetoothClass.Device.Major.PHONE) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Tries the targets in turn, then again every {@code pauseMs}, until {@code live} is cleared. The list
     * is read again each round, so a phone paired while waiting is tried too. A phone that took the
     * credentials ends its round, so it can join the hotspot before anyone else is asked.
     */
    static void nudge(Phones phones, AtomicBoolean live, long pauseMs, Log log) throws InterruptedException {
        while (live.get()) {
            List<String> macs = phones.targets();
            if (macs.isEmpty()) log.log("wireless: no paired phone to reach, waiting for one to connect");
            for (String mac : macs) {
                if (!live.get()) return;
                try {
                    phones.reach(mac);
                    break;
                } catch (IOException e) {
                    log.log("wireless: trigger to " + mac + " failed (unverified path): " + e.getMessage());
                }
            }
            Thread.sleep(pauseMs);
        }
    }

    // ---- the RFCOMM exchange (§11.3–11.5), pure streams so it is unit-testable ------------------

    public interface Log {
        void log(String line);
    }

    /**
     * Head-unit side of the credential exchange. Sends WifiStartRequest, requires WifiInfoRequest,
     * answers WifiInfoResponse, then reads up to two further messages (version request / connect
     * status / start response) whose bodies the reference implementations do not parse either.
     */
    public static void exchange(InputStream rawIn, OutputStream out, WifiInfo info, Log log) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        send(out, WIFI_START_REQUEST, new ProtoWriter(64).string(1, info.ip).int32(2, info.port));
        int[] id = new int[1];
        byte[] body = read(in, id);
        if (id[0] != WIFI_INFO_REQUEST) {
            throw new IOException("expected WifiInfoRequest (2), got message " + id[0] + " (" + body.length + " bytes)");
        }
        log.log("wireless: phone asked for Wi-Fi credentials");
        send(out, WIFI_INFO_RESPONSE, new ProtoWriter(128)
                .string(1, info.ssid).string(2, info.key).string(3, info.bssid)
                .uint(4, SECURITY_WPA2_PERSONAL).uint(5, ACCESS_POINT_DYNAMIC));
        for (int i = 0; i < 2; i++) {
            try {
                body = read(in, id);
            } catch (EOFException e) {
                return;
            }
            log.log("wireless: phone sent message " + id[0] + " (" + body.length + " bytes)" + describe(id[0], body));
        }
    }

    private static String describe(int id, byte[] body) {
        if (id != WIFI_CONNECT_STATUS || body.length == 0) return "";
        try {
            ProtoReader r = new ProtoReader(body, 0, body.length);
            if (r.next() && r.wireType() == 0) return ", status " + r.int32();
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    static void send(OutputStream out, int id, ProtoWriter body) throws IOException {
        int len = body.size();
        byte[] frame = new byte[4 + len];
        frame[0] = (byte) (len >> 8);
        frame[1] = (byte) len;
        frame[2] = (byte) (id >> 8);
        frame[3] = (byte) id;
        System.arraycopy(body.buffer(), 0, frame, 4, len);
        out.write(frame);
        out.flush();
    }

    static byte[] read(DataInputStream in, int[] idOut) throws IOException {
        int len = in.readUnsignedShort();
        idOut[0] = in.readUnsignedShort();
        if (len > 4096) throw new IOException("RFCOMM message too large: " + len);
        byte[] body = new byte[len];
        in.readFully(body);
        return body;
    }
}
