package me.ri3d.openauto.aa;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Not a unit test: an opt-in fake phone that listens on a real port so the app on an emulator or
 * device can connect to it (the emulator reaches the host at 10.0.2.2). Run with
 * <pre>FAKE_PHONE_PORT=5277 FAKE_PHONE_VIDEO=path/to/stream.h264 FAKE_PHONE_SECONDS=120 gradlew :app:testDebugUnitTest --tests '*FakePhoneServerTest*' -i</pre>
 * The video file is an H.264 Annex B elementary stream; without it one random frame is sent.
 * Serves one connection after another until the time is up.
 */
public class FakePhoneServerTest {

    @Test
    public void serve() throws Exception {
        String portEnv = System.getenv("FAKE_PHONE_PORT");
        Assume.assumeTrue("FAKE_PHONE_PORT not set; skipping the interactive fake phone", portEnv != null);
        int port = Integer.parseInt(portEnv);
        int seconds = System.getenv("FAKE_PHONE_SECONDS") == null ? 120 : Integer.parseInt(System.getenv("FAKE_PHONE_SECONDS"));
        List<byte[]> video = null;
        String videoPath = System.getenv("FAKE_PHONE_VIDEO");
        if (videoPath != null) {
            video = splitAccessUnits(Files.readAllBytes(new File(videoPath).toPath()));
            log(video.size() + " access units from " + videoPath);
        }
        TlsCredentials creds = LoopbackTlsServer.testCredentials();
        final ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"));
        log("listening on " + port + " for " + seconds + " s");
        final long end = System.currentTimeMillis() + seconds * 1000L;
        Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(end - System.currentTimeMillis());
                server.close(); // unblocks accept(); the loop below then exits
            } catch (Exception ignored) {
            }
        });
        deadline.setDaemon(true);
        deadline.start();
        // FAKE_PHONE_DROP_AFTER=<seconds>: cut the first session abruptly (phone walks away / Wi-Fi loss)
        final int dropAfter = System.getenv("FAKE_PHONE_DROP_AFTER") == null ? 0 : Integer.parseInt(System.getenv("FAKE_PHONE_DROP_AFTER"));
        boolean first = true;
        while (!server.isClosed()) {
            final FakePhone phone = new FakePhone(creds, server, video, true);
            Thread t = new Thread(phone, "fake-phone");
            t.start();
            if (dropAfter > 0 && first) {
                first = false;
                if (!phone.finished.await(dropAfter, TimeUnit.SECONDS) && phone.sock != null) {
                    log("dropping the connection abruptly after " + dropAfter + " s");
                    phone.close();
                }
            }
            phone.finished.await();
            if (phone.sock != null) {
                log("session summary: video sent " + phone.videoSent + ", acks " + phone.videoAcks + ", waits for an ack " + phone.videoHeldBack + ", touches " + phone.touches
                        + (phone.error == null ? "" : ", error " + phone.error));
            }
            phone.close();
            t.join(2000);
        }
        log("server stopped");
    }

    private static void log(String s) {
        System.out.println(java.time.LocalTime.now().toString().substring(0, 12) + " [fake-phone] " + s);
        System.out.flush();
    }

    /** Splits an Annex B stream into access units: an AUD (type 9) starts one, else each VCL NAL ends one. */
    static List<byte[]> splitAccessUnits(byte[] s) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 3 < s.length; i++) {
            if (s[i] == 0 && s[i + 1] == 0 && s[i + 2] == 1) {
                int sc = (i > 0 && s[i - 1] == 0) ? i - 1 : i;
                if (starts.isEmpty() || starts.get(starts.size() - 1) != sc) starts.add(sc);
                i += 2;
            }
        }
        List<byte[]> units = new ArrayList<>();
        int auStart = starts.isEmpty() ? 0 : starts.get(0);
        for (int n = 0; n < starts.size(); n++) {
            int nalStart = starts.get(n);
            int nalEnd = n + 1 < starts.size() ? starts.get(n + 1) : s.length;
            int hdr = nalStart + ((s[nalStart + 2] == 1) ? 3 : 4);
            int type = s[hdr] & 0x1F;
            boolean vcl = type >= 1 && type <= 5;
            boolean nextIsAud = n + 1 < starts.size() && (s[starts.get(n + 1) + ((s[starts.get(n + 1) + 2] == 1) ? 3 : 4)] & 0x1F) == 9;
            if (vcl || nextIsAud || n + 1 == starts.size()) {
                units.add(java.util.Arrays.copyOfRange(s, auStart, nalEnd));
                auStart = nalEnd;
            }
        }
        return units;
    }
}
