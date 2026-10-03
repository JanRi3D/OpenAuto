package me.ri3d.openauto.aa;

import org.junit.Assume;
import org.junit.Test;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.ri3d.openauto.transport.TcpTransport;

/**
 * Not a unit test: runs the production Session from this JVM against a REAL phone whose Android
 * Auto "head unit server" is listening, with full message tracing. Opt-in:
 * <pre>REAL_PHONE_HOST=10.0.10.121 [REAL_PHONE_PORT=5277] [REAL_PHONE_SECONDS=20] [REAL_PHONE_DUMP=video.h264]
 * gradlew :app:testDebugUnitTest --tests '*RealPhoneProbeTest*' -i</pre>
 * Media goes to counting stubs; video can be dumped to a file for inspection.
 */
public class RealPhoneProbeTest {

    private static void say(String s) {
        System.out.println(java.time.LocalTime.now().toString().substring(0, 12) + " [probe] " + s);
        System.out.flush();
    }

    @Test
    public void probe() throws Exception {
        String host = System.getenv("REAL_PHONE_HOST");
        Assume.assumeTrue("REAL_PHONE_HOST not set; skipping the real-phone probe", host != null);
        int port = System.getenv("REAL_PHONE_PORT") == null ? 5277 : Integer.parseInt(System.getenv("REAL_PHONE_PORT"));
        int seconds = System.getenv("REAL_PHONE_SECONDS") == null ? 20 : Integer.parseInt(System.getenv("REAL_PHONE_SECONDS"));
        String dump = System.getenv("REAL_PHONE_DUMP");
        final OutputStream dumpOut = dump == null ? null : new FileOutputStream(dump);

        final long[] video = new long[3]; // units, bytes, first-unit logged
        final long[] audio = new long[9];
        Media media = new Media() {
            final VideoOut v = new VideoOut() {
                public boolean open(int width, int height, int fps) { say("media: video open " + width + "x" + height + "@" + fps); return true; }
                public void write(byte[] data, int off, int len, long ptsUs) {
                    video[0]++;
                    video[1] += len;
                    if (video[0] <= 5) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(len, 40); i++) sb.append(String.format("%02x ", data[off + i]));
                        say("media: video unit " + video[0] + " len " + len + " pts " + ptsUs + " : " + sb);
                    }
                    if (dumpOut != null) {
                        try { dumpOut.write(data, off, len); } catch (Exception ignored) { }
                    }
                }
                public void close() { }
            };
            public VideoOut video() { return v; }
            public AudioOut audio(final int channelId) {
                return new AudioOut() {
                    public boolean open(int sampleRate, int channels) { say("media: audio " + Wire.channelName(channelId) + " open " + sampleRate + "x" + channels); return true; }
                    public void write(byte[] data, int off, int len) { audio[channelId] += len; }
                    public void stop() { say("media: audio " + Wire.channelName(channelId) + " stop"); }
                    public void close() { }
                    public void setDucked(boolean ducked) { say("media: duck " + ducked); }
                };
            }
            public MicIn mic() {
                return new MicIn() {
                    public boolean open(int sampleRate, Listener listener) { say("media: mic open " + sampleRate); return true; }
                    public void close() { say("media: mic close"); }
                };
            }
        };

        final CountDownLatch closed = new CountDownLatch(1);
        Session.Config cfg = new Session.Config();
        cfg.trace = true;
        if (System.getenv("REAL_PHONE_RES") != null) cfg.setResolution(Integer.parseInt(System.getenv("REAL_PHONE_RES")));
        if (System.getenv("REAL_PHONE_DPI") != null) cfg.dpi = Integer.parseInt(System.getenv("REAL_PHONE_DPI"));
        if (System.getenv("REAL_PHONE_MARGIN_W") != null) cfg.marginWidth = Integer.parseInt(System.getenv("REAL_PHONE_MARGIN_W"));
        if (System.getenv("REAL_PHONE_MARGIN_H") != null) cfg.marginHeight = Integer.parseInt(System.getenv("REAL_PHONE_MARGIN_H"));
        if (System.getenv("REAL_PHONE_NO_MIC") != null) cfg.mic = false; // does the phone accept a head unit without a microphone?
        say("video config: " + cfg.videoWidth + "x" + cfg.videoHeight + " dpi " + cfg.dpi + " margins " + cfg.marginWidth + "x" + cfg.marginHeight);
        // Optional scripted taps "x,y;x,y" in video pixels, one every 4 s starting 8 s in (to observe the phone's reaction in the dump).
        final String taps = System.getenv("REAL_PHONE_TAPS");
        Session.Listener l = new Session.Listener() {
            public void onState(Session s, Session.State state, String detail) {
                say("STATE " + state + (detail == null ? "" : " : " + detail));
                if (state == Session.State.CLOSED) closed.countDown();
            }
            public void onLog(Session s, String line) { say(line); }
        };
        say("connecting to " + host + ":" + port);
        Session session = new Session(TcpTransport.connect(host, port, 8000), LoopbackTlsServer.testCredentials(), cfg, media, l);
        session.start();
        if (taps != null) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(8000);
                    for (String tap : taps.split(";")) {
                        String[] xy = tap.split(",");
                        int x = Integer.parseInt(xy[0].trim()), y = Integer.parseInt(xy[1].trim());
                        say("TAP " + x + "," + y + " after video unit " + video[0]);
                        session.input.touch(Wire.TOUCH_PRESS, x, y);
                        Thread.sleep(80);
                        session.input.touch(Wire.TOUCH_RELEASE, x, y);
                        Thread.sleep(4000);
                    }
                } catch (Exception e) {
                    say("tap script: " + e);
                }
            });
            t.setDaemon(true);
            t.start();
        }
        if (!closed.await(seconds, TimeUnit.SECONDS)) {
            say("time is up, requesting shutdown");
            session.requestShutdown("probe finished");
            closed.await(3, TimeUnit.SECONDS);
        }
        say("SUMMARY state " + session.state() + ", close reason: " + session.closeReason() + ", phone " + session.phoneBrand + " " + session.phoneName
                + ", video units " + video[0] + " (" + video[1] / 1024 + " KiB), audio bytes media/speech/system "
                + audio[Wire.CH_MEDIA_AUDIO] + "/" + audio[Wire.CH_SPEECH_AUDIO] + "/" + audio[Wire.CH_SYSTEM_AUDIO]
                + ", pings sent/answered " + session.pingsSent + "/" + session.pingsAnswered);
        if (dumpOut != null) dumpOut.close();
    }
}
