package me.ri3d.openauto.wireless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.ri3d.openauto.proto.ProtoReader;
import me.ri3d.openauto.proto.ProtoWriter;

/** The RFCOMM credential exchange against a scripted phone side (protocol-reference §11.3–11.6). */
public class WifiExchangeTest {

    private static byte[] frame(int id, ProtoWriter body) {
        byte[] f = new byte[4 + body.size()];
        f[0] = (byte) (body.size() >> 8); f[1] = (byte) body.size(); f[2] = (byte) (id >> 8); f[3] = (byte) id;
        System.arraycopy(body.buffer(), 0, f, 4, body.size());
        return f;
    }

    @Test
    public void sendsStartRequestAndCredentials() throws Exception {
        ByteArrayOutputStream phoneToHu = new ByteArrayOutputStream();
        phoneToHu.write(frame(WirelessServer.WIFI_INFO_REQUEST, new ProtoWriter()));
        phoneToHu.write(frame(WirelessServer.WIFI_VERSION_REQUEST, new ProtoWriter()));
        phoneToHu.write(frame(WirelessServer.WIFI_CONNECT_STATUS, new ProtoWriter().uint(1, 0)));
        ByteArrayOutputStream huToPhone = new ByteArrayOutputStream();
        List<String> log = new ArrayList<>();

        WirelessServer.WifiInfo info = new WirelessServer.WifiInfo("OPENAUTO-1A2B", "secret1234", "02:00:00:aa:bb:cc", "192.168.43.1", 5288);
        WirelessServer.exchange(new ByteArrayInputStream(phoneToHu.toByteArray()), huToPhone, info, log::add);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(huToPhone.toByteArray()));
        int[] id = new int[1];
        byte[] start = WirelessServer.read(in, id);
        assertEquals(WirelessServer.WIFI_START_REQUEST, id[0]);
        ProtoReader r = new ProtoReader(start, 0, start.length);
        assertEquals("192.168.43.1", r.nextField(1).string());
        assertEquals(5288, r.nextField(2).int32());

        byte[] resp = WirelessServer.read(in, id);
        assertEquals(WirelessServer.WIFI_INFO_RESPONSE, id[0]);
        r = new ProtoReader(resp, 0, resp.length);
        assertEquals("OPENAUTO-1A2B", r.nextField(1).string());
        assertEquals("secret1234", r.nextField(2).string());
        assertEquals("02:00:00:aa:bb:cc", r.nextField(3).string());
        assertEquals(WirelessServer.SECURITY_WPA2_PERSONAL, r.nextField(4).int32());
        assertEquals(WirelessServer.ACCESS_POINT_DYNAMIC, r.nextField(5).int32());
        assertEquals(0, in.available());
        assertTrue(log.toString(), log.get(log.size() - 1).contains("status 0"));
    }

    @Test
    public void rejectsUnexpectedFirstMessage() throws Exception {
        byte[] wrong = frame(WirelessServer.WIFI_CONNECT_STATUS, new ProtoWriter().uint(1, 3));
        WirelessServer.WifiInfo info = new WirelessServer.WifiInfo("s", "k", "", "10.0.0.1", 5288);
        try {
            WirelessServer.exchange(new ByteArrayInputStream(wrong), new ByteArrayOutputStream(), info, l -> { });
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("expected WifiInfoRequest"));
        }
    }

    @Test
    public void truncatedMessageIsAnError() throws Exception {
        byte[] truncated = {0x00, 0x10, 0x00, 0x02, 0x01}; // claims 16 bytes, has 1
        WirelessServer.WifiInfo info = new WirelessServer.WifiInfo("s", "k", "", "10.0.0.1", 5288);
        try {
            WirelessServer.exchange(new ByteArrayInputStream(truncated), new ByteArrayOutputStream(), info, l -> { });
            fail();
        } catch (IOException expected) {
        }
    }
}
