package me.ri3d.openauto.wireless;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
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
import java.util.UUID;

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
 * best-effort trigger this server also tries an outgoing RFCOMM connection to the chosen paired phone
 * with the same UUID and runs the same exchange. Whether a phone accepts either path from a non-HSP
 * device is UNVERIFIED and must be tested with real hardware.
 */
public final class WirelessServer {
    private static final String TAG = "Wireless";
    public static final UUID AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    public static final int TCP_PORT = 5288;

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
    private volatile boolean running;
    private final java.util.List<Thread> threads = new java.util.ArrayList<>();

    public WirelessServer(Listener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // caller checks Perms.hasBluetooth()
    public synchronized void start(final BluetoothAdapter adapter, final WifiInfo info, final String phoneMac) throws IOException {
        stop();
        running = true;
        tcp = new ServerSocket(info.port, 2, InetAddress.getByName("0.0.0.0"));
        rfcomm = adapter.listenUsingRfcommWithServiceRecord("AA Wireless", AA_UUID);
        listener.onLog("wireless: RFCOMM server up, TCP " + info.ip + ":" + info.port + ", hotspot " + info.ssid);

        spawn("wireless-tcp", () -> {
            try {
                while (running) {
                    Socket s = tcp.accept();
                    listener.onLog("wireless: phone connected over Wi-Fi from " + s.getInetAddress().getHostAddress());
                    listener.onPhone(s);
                    return;
                }
            } catch (IOException e) {
                if (running) listener.onFailed("TCP accept failed: " + e.getMessage());
            }
        });

        spawn("wireless-rfcomm", () -> {
            while (running) {
                try {
                    BluetoothSocket s = rfcomm.accept();
                    listener.onLog("wireless: phone connected over Bluetooth: " + s.getRemoteDevice().getAddress());
                    try {
                        exchange(s.getInputStream(), s.getOutputStream(), info, listener::onLog);
                    } finally {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                } catch (IOException e) {
                    if (running) listener.onLog("wireless: RFCOMM " + e.getMessage());
                    if (!running) return;
                }
            }
        });

        if (phoneMac != null) {
            spawn("wireless-trigger", () -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                BluetoothDevice dev = adapter.getRemoteDevice(phoneMac);
                listener.onLog("wireless: trying to reach " + phoneMac + " over RFCOMM (unverified trigger)");
                BluetoothSocket s = null;
                try {
                    adapter.cancelDiscovery();
                    s = dev.createRfcommSocketToServiceRecord(AA_UUID);
                    s.connect();
                    listener.onLog("wireless: outgoing RFCOMM connected");
                    exchange(s.getInputStream(), s.getOutputStream(), info, listener::onLog);
                } catch (IOException e) {
                    listener.onLog("wireless: outgoing RFCOMM failed: " + e.getMessage());
                } finally {
                    if (s != null) try { s.close(); } catch (IOException ignored) { }
                }
            });
        }
    }

    private void spawn(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    public synchronized void stop() {
        running = false;
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

    public boolean isRunning() {
        return running;
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
