package me.ri3d.openauto.proto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProtoCodecTest {

    @Test
    public void varintsMatchProtobufReference() {
        // Known encodings from the protobuf spec: 1 -> 01, 150 -> 96 01, 300 -> AC 02, -1 (int32) -> 10 bytes of FF..01
        assertArrayEquals(new byte[]{0x08, 0x01}, new ProtoWriter().uint(1, 1).toBytes());
        assertArrayEquals(new byte[]{0x08, (byte) 0x96, 0x01}, new ProtoWriter().uint(1, 150).toBytes());
        assertArrayEquals(new byte[]{0x08, (byte) 0xAC, 0x02}, new ProtoWriter().uint(1, 300).toBytes());
        byte[] neg = new ProtoWriter().int32(1, -1).toBytes();
        assertEquals(11, neg.length);
        assertEquals(-1, new ProtoReader(neg, 0, neg.length).nextField(1).int32());
    }

    @Test
    public void roundTripsStringsMessagesAndFixed64() {
        ProtoWriter inner = new ProtoWriter().uint(1, 800).uint(2, 480);
        byte[] msg = new ProtoWriter().string(2, "OpenAuto").message(3, inner).fixed64(4, 0x0102030405060708L).bool(5, true).toBytes();
        ProtoReader r = new ProtoReader(msg, 0, msg.length);
        assertTrue(r.next());
        assertEquals(2, r.field());
        assertEquals("OpenAuto", r.string());
        assertTrue(r.next());
        assertEquals(3, r.field());
        ProtoReader sub = r.message(new ProtoReader());
        assertTrue(sub.next());
        assertEquals(800, sub.int32());
        assertTrue(sub.next());
        assertEquals(480, sub.int32());
        assertFalse(sub.next());
        assertTrue(r.next());
        assertEquals(0x0102030405060708L, r.fixed64());
        assertTrue(r.next());
        assertTrue(r.bool());
        assertFalse(r.next());
    }

    @Test
    public void skipsUnknownFields() {
        byte[] msg = new ProtoWriter().uint(9, 7).string(10, "x").fixed64(11, 1).uint(1, 42).toBytes();
        ProtoReader r = new ProtoReader(msg, 0, msg.length);
        int value = -1;
        while (r.next()) {
            if (r.field() == 1) value = r.int32(); else r.skip();
        }
        assertEquals(42, value);
    }

    @Test
    public void rejectsTruncatedInput() {
        byte[] full = new ProtoWriter().string(1, "hello world").toBytes();
        for (int cut = 1; cut < full.length; cut++) {
            ProtoReader r = new ProtoReader(full, 0, cut);
            try {
                while (r.next()) r.string();
                fail("accepted truncated message cut at " + cut);
            } catch (ProtoReader.ProtoException expected) {
                // every truncation must be reported, never silently read past the end
            }
        }
    }

    @Test
    public void rejectsOversizedLengthAndBadVarint() {
        byte[] bad = {0x0A, 0x7F, 0x01}; // field 1, length 127, one byte present
        try {
            new ProtoReader(bad, 0, bad.length).nextField(1).bytes();
            fail();
        } catch (ProtoReader.ProtoException expected) { }
        byte[] longVarint = new byte[12];
        java.util.Arrays.fill(longVarint, (byte) 0xFF);
        try {
            new ProtoReader(longVarint, 0, longVarint.length).varint();
            fail();
        } catch (ProtoReader.ProtoException expected) { }
    }
}
