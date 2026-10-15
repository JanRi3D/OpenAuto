package me.ri3d.openauto.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Which phones the wireless trigger reaches for, and how it keeps trying until one arrives. */
public class WirelessTriggerTest {
    private static final int PHONE = 0x0200, AUDIO_VIDEO = 0x0400, UNKNOWN = -1; // BluetoothClass.Device.Major

    @Test
    public void anyPairedPhoneMeansEveryBondedPhoneInOrder() {
        Map<String, Integer> bonded = new LinkedHashMap<>();
        bonded.put("AA:00:00:00:00:01", PHONE);
        bonded.put("AA:00:00:00:00:02", AUDIO_VIDEO); // the car's own headset or speaker
        bonded.put("AA:00:00:00:00:03", UNKNOWN);
        bonded.put("AA:00:00:00:00:04", PHONE);

        assertEquals(Arrays.asList("AA:00:00:00:00:01", "AA:00:00:00:00:04"), WirelessServer.targets(null, bonded));
        // A chosen device is the only target, whatever class it reports and even when it is not bonded (any more).
        assertEquals(Collections.singletonList("AA:00:00:00:00:02"), WirelessServer.targets("AA:00:00:00:00:02", bonded));
        assertEquals(Collections.singletonList("BB:00:00:00:00:09"), WirelessServer.targets("BB:00:00:00:00:09", bonded));
        assertEquals(Collections.emptyList(), WirelessServer.targets(null, new LinkedHashMap<>()));
    }

    @Test(timeout = 5000)
    public void keepsTryingEveryRoundUntilThePhoneArrives() throws Exception {
        final AtomicBoolean live = new AtomicBoolean(true);
        final List<String> paired = new ArrayList<>(Collections.singletonList("A"));
        final List<String> tried = new ArrayList<>();
        final int[] round = {0};
        WirelessServer.Phones phones = new WirelessServer.Phones() {
            @Override public List<String> targets() {
                if (++round[0] == 3) paired.add("B"); // paired while the head unit waits
                return new ArrayList<>(paired);
            }

            @Override public void reach(String mac) throws IOException {
                tried.add(round[0] + mac);
                if (round[0] == 4 && mac.equals("B")) {
                    live.set(false); // took the credentials and reached TCP
                    return;
                }
                throw new IOException("Service discovery failed");
            }
        };
        List<String> log = new ArrayList<>();

        WirelessServer.nudge(phones, live, 1, log::add);

        // Failures never end the loop, a phone paired meanwhile joins the next round, and the loop ends once
        // the phone is on Wi-Fi.
        assertEquals(Arrays.asList("1A", "2A", "3A", "3B", "4A", "4B"), tried);
        assertEquals(4, round[0]);
        assertEquals(5, log.size());
        assertTrue(log.get(0), log.get(0).contains("trigger to A") && log.get(0).contains("Service discovery failed"));
    }

    @Test(timeout = 5000)
    public void aPhoneThatTookTheCredentialsEndsItsRound() throws Exception {
        final AtomicBoolean live = new AtomicBoolean(true);
        final List<String> tried = new ArrayList<>();
        WirelessServer.Phones phones = new WirelessServer.Phones() {
            @Override public List<String> targets() {
                return Arrays.asList("A", "B", "C");
            }

            @Override public void reach(String mac) throws IOException {
                tried.add(mac);
                if (tried.size() == 5) live.set(false);
                if (mac.equals("A")) throw new IOException("Host is down");
            }
        };

        WirelessServer.nudge(phones, live, 1, l -> { });

        // B answered, so C is not asked in that round and the next round starts again from A; once the phone
        // is on Wi-Fi (during the fifth attempt) nobody else is asked.
        assertEquals(Arrays.asList("A", "B", "A", "B", "A"), tried);
    }

    @Test(timeout = 5000)
    public void stopWakesTheTriggerFromItsPause() throws Exception {
        final AtomicBoolean live = new AtomicBoolean(true);
        WirelessServer.Phones none = new WirelessServer.Phones() {
            @Override public List<String> targets() { return Collections.emptyList(); }
            @Override public void reach(String mac) { }
        };
        final Throwable[] ended = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                WirelessServer.nudge(none, live, WirelessServer.RETRY_MS, l -> { });
            } catch (Throwable e) {
                ended[0] = e;
            }
        });
        t.start();
        Thread.sleep(100);
        t.interrupt(); // what WirelessServer.stop() does
        t.join(2000);
        assertTrue("ended with " + ended[0], !t.isAlive() && ended[0] instanceof InterruptedException);
    }
}
