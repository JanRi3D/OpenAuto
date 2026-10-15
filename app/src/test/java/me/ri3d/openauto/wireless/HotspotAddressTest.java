package me.ri3d.openauto.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which interface address the phone is sent to as the head unit's access point. */
public class HotspotAddressTest {
    private final Map<String, List<InetAddress>> interfaces = new LinkedHashMap<>();

    private void add(String name, String... addresses) throws Exception {
        List<InetAddress> list = new ArrayList<>();
        for (String a : addresses) list.add(InetAddress.getByName(a)); // literals: no DNS lookup
        interfaces.put(name, list);
    }

    @Test
    public void stationOnWlan0LosesToTheHotspotInterface() throws Exception {
        add("lo", "127.0.0.1");
        add("wlan0", "fec0::1", "192.168.1.37"); // Wi-Fi client to the home network
        add("rmnet_data0", "10.64.12.3");
        add("wlan1", "192.168.49.1"); // local-only hotspot
        assertEquals("192.168.49.1", Hotspot.apAddress(interfaces));

        interfaces.remove("wlan1");
        add("ap0", "192.168.43.1");
        assertEquals("192.168.43.1", Hotspot.apAddress(interfaces));
    }

    @Test
    public void wlan0IsTheFallbackWhenItIsTheOnlyAccessPoint() throws Exception {
        add("wlan0", "192.168.43.1");
        add("p2p0", "192.168.49.1"); // Wi-Fi Direct, not ours
        assertEquals("192.168.43.1", Hotspot.apAddress(interfaces));
    }

    @Test
    public void publicAndIpv6AddressesNeverCount() throws Exception {
        add("swlan0", "8.8.4.4", "fec0::2");
        add("eth0", "192.168.0.2");
        assertNull(Hotspot.apAddress(interfaces));
    }
}
