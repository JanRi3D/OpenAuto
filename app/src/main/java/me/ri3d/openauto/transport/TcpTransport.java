package me.ri3d.openauto.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Plain TCP to the phone (manual mode 5277, wireless 5288 accepted socket, Self Mode loopback). */
public final class TcpTransport implements Transport {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String label;

    public TcpTransport(Socket connected, String label) throws IOException {
        socket = connected;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        this.label = label;
    }

    /** Connects with a timeout; the caller cancels by closing the returned transport from another thread. */
    public static TcpTransport connect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        return new TcpTransport(s, "TCP " + host + ":" + port);
    }

    @Override
    public int read(byte[] buf, int maxLen) throws IOException {
        return in.read(buf, 0, maxLen);
    }

    @Override
    public void write(byte[] buf, int len) throws IOException {
        out.write(buf, 0, len);
        out.flush();
    }

    @Override
    public String describe() {
        return label;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
