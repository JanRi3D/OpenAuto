package me.ri3d.openauto.aa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.proto.ProtoWriter;
import me.ri3d.openauto.transport.TcpTransport;

/**
 * Drives a complete Session against {@link FakePhone}. It checks OUR side of the protocol: message
 * order, encodings, acks, encryption boundaries. It cannot prove what Google's app does.
 */
public class FakePhoneTest {

    static final class StubMedia implements Media {
        volatile int videoW, videoH, videoFps;
        volatile long videoBytes, videoFrames;
        final List<String> audioOpens = Collections.synchronizedList(new ArrayList<>());
        volatile long mediaAudioBytes;
        volatile boolean micOpened;

        final VideoOut video = new VideoOut() {
            public boolean open(int width, int height, int fps) { videoW = width; videoH = height; videoFps = fps; return true; }
            public void write(byte[] data, int off, int len, long ptsUs) { videoFrames++; videoBytes += len; }
            public void close() { }
        };

        public VideoOut video() { return video; }

        public AudioOut audio(final int channelId) {
            return new AudioOut() {
                public boolean open(int sampleRate, int channels) { audioOpens.add(channelId + ":" + sampleRate + "x" + channels); return true; }
                public void write(byte[] data, int off, int len) { if (channelId == Wire.CH_MEDIA_AUDIO) mediaAudioBytes += len; }
                public void stop() { }
                public void close() { }
                public void setDucked(boolean ducked) { }
            };
        }

        public MicIn mic() {
            return new MicIn() {
                public boolean open(int sampleRate, final Listener listener) {
                    micOpened = true;
                    new Thread(() -> {
                        try { Thread.sleep(30); } catch (InterruptedException ignored) { }
                        listener.onMicData(new byte[320], 320, 123456789L);
                    }).start();
                    return true;
                }
                public void close() { }
            };
        }
    }

    @Test(timeout = 30000)
    public void fullStartupVideoAudioMicAndShutdown() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        FakePhone phone = new FakePhone(creds, server, null, false);
        Thread pt = new Thread(phone, "fake-phone");
        pt.start();

        StubMedia media = new StubMedia();
        final List<Session.State> states = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch projecting = new CountDownLatch(1), closed = new CountDownLatch(1), routed = new CountDownLatch(1);
        final String[] closeReason = new String[1], turn = new String[1];
        Session.Config cfg = new Session.Config();
        cfg.pingIntervalMs = 200;
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) {
                states.add(state);
                if (state == Session.State.PROJECTING) projecting.countDown();
                if (state == Session.State.CLOSED) { closeReason[0] = detail; closed.countDown(); }
            }
            public void onLog(Session s, String line) { }
            public void onNavigation(Session s) {
                NavChannel n = s.nav;
                if (n.meters < 0) return; // distance follows the turn
                turn[0] = n.status + " " + n.road + " dir " + n.direction + " type " + n.maneuver + " png " + n.image.length
                        + " " + n.meters + " m " + n.seconds + " s " + n.displayMillis + " unit " + n.unit;
                routed.countDown();
            }
        };
        Session session = new Session(TcpTransport.connect("127.0.0.1", server.getLocalPort(), 2000), creds, cfg, media, l);
        session.start();

        assertTrue("video START never arrived", projecting.await(15, TimeUnit.SECONDS));
        assertTrue("video media not acked", phone.mediaAcked.await(5, TimeUnit.SECONDS));
        assertTrue("media audio not acked", phone.audioAcked.await(5, TimeUnit.SECONDS));
        assertTrue("mic data not received by phone", phone.micData.await(5, TimeUnit.SECONDS));
        assertTrue("input binding not answered", phone.bound.await(5, TimeUnit.SECONDS));
        assertTrue("sensor events missing", phone.sensors.await(5, TimeUnit.SECONDS));
        assertTrue("no ping sent", phone.pinged.await(5, TimeUnit.SECONDS));
        assertTrue("navigation events missing", routed.await(5, TimeUnit.SECONDS));
        assertEquals(Wire.NAV_ACTIVE + " Hauptstraße dir 1 type 4 png 4 352 m 25 s 350000 unit 1", turn[0]);

        session.input.touch(Wire.TOUCH_PRESS, 100, 200);
        session.input.touch(Wire.TOUCH_RELEASE, 100, 200);

        phone.send(0, false, Wire.AUDIO_FOCUS_REQUEST, FakePhone.proto(new ProtoWriter().uint(1, Wire.AUDIO_FOCUS_GAIN)), true);
        assertTrue("no audio focus response", phone.focusResp.await(5, TimeUnit.SECONDS));
        phone.send(0, false, Wire.SHUTDOWN_REQUEST, FakePhone.proto(new ProtoWriter().uint(1, 1)), true);
        assertTrue("shutdown not acknowledged", phone.shutdownAcked.await(5, TimeUnit.SECONDS));
        assertTrue("session did not close", closed.await(5, TimeUnit.SECONDS));

        if (phone.error != null) throw phone.error;
        assertEquals("the phone ended the session", closeReason[0]);
        assertEquals(800, media.videoW);
        assertEquals(480, media.videoH);
        assertEquals(1, media.videoFrames);
        assertEquals(100_000, media.videoBytes);
        assertEquals(3840, media.mediaAudioBytes);
        assertTrue(media.micOpened);
        assertTrue(media.audioOpens.toString(), media.audioOpens.contains("4:48000x2"));
        assertTrue(phone.events.toString(), phone.events.contains("video-focused"));
        assertTrue(phone.events.toString(), phone.events.contains("touch"));
        assertEquals(Session.State.VERSION, states.get(0));
        assertTrue(states.indexOf(Session.State.HANDSHAKE) < states.indexOf(Session.State.DISCOVERY));
        assertTrue(states.indexOf(Session.State.DISCOVERY) < states.indexOf(Session.State.READY));
        assertTrue(states.indexOf(Session.State.READY) < states.indexOf(Session.State.PROJECTING));
        assertEquals("Test Fake Phone", session.phoneBrand + " " + session.phoneName);
        phone.close();
        server.close();
        pt.join(2000);
    }

    @Test(timeout = 15000)
    public void headUnitInitiatedShutdownIsAnsweredAndClosed() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        FakePhone phone = new FakePhone(creds, server, null, false);
        new Thread(phone, "fake-phone").start();
        final CountDownLatch ready = new CountDownLatch(1), closed = new CountDownLatch(1);
        final String[] reason = new String[1];
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) {
                if (state == Session.State.READY) ready.countDown();
                if (state == Session.State.CLOSED) { reason[0] = detail; closed.countDown(); }
            }
            public void onLog(Session s, String line) { }
        };
        Session session = new Session(TcpTransport.connect("127.0.0.1", server.getLocalPort(), 2000), creds, new Session.Config(), new StubMedia(), l);
        session.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        session.requestShutdown("stopped by test");
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals("stopped by test", reason[0]);
        assertTrue(phone.events.toString(), phone.events.contains("head unit requested shutdown"));
        phone.close();
        server.close();
    }

    @Test(timeout = 15000)
    public void versionMismatchClosesCleanly() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch closed = new CountDownLatch(1);
        final String[] reason = new String[1];
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) { if (state == Session.State.CLOSED) { reason[0] = detail; closed.countDown(); } }
            public void onLog(Session s, String line) { }
        };
        Session session = new Session(TcpTransport.connect("127.0.0.1", ss.getLocalPort(), 2000), creds, new Session.Config(), new StubMedia(), l);
        session.start();
        Socket s = ss.accept();
        InputStream in = s.getInputStream();
        byte[] req = new byte[10];
        new DataInputStream(in).readFully(req);
        assertEquals(Wire.VERSION_REQUEST, req[5]);
        s.getOutputStream().write(new byte[]{0, 3, 0, 8, 0, 2, 0, 1, 0, 1, (byte) 0xFF, (byte) 0xFF});
        s.getOutputStream().flush();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(reason[0], reason[0].contains("version mismatch"));
        s.close();
        ss.close();
    }

    @Test(timeout = 15000)
    public void silentPhoneTimesOutWithAnActionableReason() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch closed = new CountDownLatch(1);
        final String[] reason = new String[1];
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) { if (state == Session.State.CLOSED) { reason[0] = detail; closed.countDown(); } }
            public void onLog(Session s, String line) { }
        };
        Session.Config cfg = new Session.Config();
        cfg.handshakeTimeoutMs = 600;
        Session session = new Session(TcpTransport.connect("127.0.0.1", ss.getLocalPort(), 2000), creds, cfg, new StubMedia(), l);
        long t0 = System.currentTimeMillis();
        session.start();
        Socket s = ss.accept(); // accepts, reads nothing, answers nothing: what the real phone did
        assertTrue("no timeout", closed.await(5, TimeUnit.SECONDS));
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        assertTrue(reason[0], reason[0].contains("did not answer") && reason[0].contains("head unit server"));
        s.close();
        ss.close();
    }

    @Test(timeout = 15000)
    public void truncatedFrameFromPhoneEndsSessionWithoutHanging() throws Exception {
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch closed = new CountDownLatch(1);
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) { if (state == Session.State.CLOSED) closed.countDown(); }
            public void onLog(Session s, String line) { }
        };
        Session session = new Session(TcpTransport.connect("127.0.0.1", ss.getLocalPort(), 2000), creds, new Session.Config(), new StubMedia(), l);
        session.start();
        Socket s = ss.accept();
        new DataInputStream(s.getInputStream()).readFully(new byte[10]);
        s.getOutputStream().write(new byte[]{0, 3, 0, 8, 0, 2, 0, 1});
        s.getOutputStream().flush();
        s.close();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(session.isClosed());
        ss.close();
    }
}
