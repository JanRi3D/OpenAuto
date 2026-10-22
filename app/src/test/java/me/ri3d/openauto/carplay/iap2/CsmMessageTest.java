package me.ri3d.openauto.carplay.iap2;

import static me.ri3d.openauto.carplay.auth.LocalMfiAuthenticatorTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import me.ri3d.openauto.carplay.iap2.CsmMessage.Parameter;

/** Control session message framing against hand-written bytes. */
public class CsmMessageTest {

    @Test
    public void encodesTheDocumentedLayout() {
        assertArrayEquals(hex("4040" + "0006" + "1D00"), new CsmMessage(0x1D00).encode());
        assertArrayEquals(hex("4040" + "000D" + "AA01" + "0007" + "0000" + "010203"),
                new CsmMessage(0xAA01, new Parameter(0, new byte[]{1, 2, 3})).encode());
    }

    @Test
    public void keepsParameterOrderAndRepeatedIds() throws Exception {
        byte[] wire = hex("4040" + "0015" + "1D01" + "0005" + "0005" + "61" + "0004" + "0001" + "0006" + "0005" + "6263");
        CsmMessage m = CsmMessage.decode(wire, 0, wire.length);
        assertEquals(0x1D01, m.id);
        assertEquals(3, m.parameters().size());
        int[] ids = {5, 1, 5};
        String[] values = {"a", "", "bc"};
        for (int i = 0; i < 3; i++) {
            assertEquals(ids[i], m.parameters().get(i).id);
            assertEquals(values[i], new String(m.parameters().get(i).value(), "US-ASCII"));
        }
        assertArrayEquals("re-encodes to the same bytes", wire, m.encode());
        assertArrayEquals(new byte[0], m.single(1));
        assertNull(m.single(9));
        try {
            m.single(5);
            fail("a repeated parameter read as single");
        } catch (ProtocolException expected) {
            // ambiguous on purpose
        }
    }

    @Test
    public void groupParametersNest() throws Exception {
        List<Parameter> inner = Arrays.asList(new Parameter(0, new byte[]{7}), new Parameter(1, new byte[]{8, 9}));
        byte[] group = CsmMessage.encodeParameters(inner);
        assertArrayEquals(hex("0005" + "0000" + "07" + "0006" + "0001" + "0809"), group);
        byte[] wire = new CsmMessage(0x1D01, new Parameter(10, group)).encode();
        List<Parameter> back = CsmMessage.decodeParameters(CsmMessage.decode(wire, 0, wire.length).single(10), 0, group.length);
        assertEquals(2, back.size());
        assertArrayEquals(new byte[]{8, 9}, back.get(1).value());
    }

    @Test
    public void decodeRejectsInvalidLengths() {
        String[] bad = {
                "4041" + "0006" + "1D00",                       // start bytes
                "4040" + "0007" + "1D00",                       // length field larger than the message
                "4040" + "0005" + "1D00",                       // length field smaller than the message
                "4040" + "0009" + "1D00" + "000003",            // 3 bytes after the last parameter
                "4040" + "000A" + "AA02" + "0003" + "0000",     // parameter shorter than its header
                "4040" + "000A" + "AA02" + "0000" + "0000",     // parameter length 0
                "4040" + "000A" + "AA02" + "0008" + "0000",     // parameter runs past the message
                "4040" + "00",                                  // shorter than a header
        };
        for (String s : bad) {
            byte[] b = hex(s);
            try {
                CsmMessage.decode(b, 0, b.length);
                fail(s + " decoded");
            } catch (ProtocolException expected) {
                // each one is a framing error
            }
        }
    }

    @Test
    public void encodeStaysWithin16Bits() {
        byte[] max = new CsmMessage(0x1D01, new Parameter(0, new byte[CsmMessage.MAX_PARAMETER_VALUE])).encode();
        assertEquals(0xFFFF, max.length);
        assertEquals("FFFF", String.format("%02X%02X", max[2], max[3]));
        expectIllegal(() -> new CsmMessage(0x1D01, new Parameter(0, new byte[40000]), new Parameter(1, new byte[40000])).encode());
        expectIllegal(() -> new Parameter(0, new byte[CsmMessage.MAX_PARAMETER_VALUE + 1]));
        expectIllegal(() -> new CsmMessage(0x10000));
        expectIllegal(() -> new Parameter(-1, new byte[0]));
    }

    @Test
    public void readerReassemblesAnySplitOrJoin() throws Exception {
        byte[] large = new byte[1000];
        new Random(1).nextBytes(large);
        byte[] stream = concat(new CsmMessage(0x1D00).encode(),
                new CsmMessage(0xAA02, new Parameter(0, new byte[32])).encode(),
                new CsmMessage(0xAA01, new Parameter(0, large), new Parameter(0, new byte[0])).encode());
        for (int cut = 0; cut <= stream.length; cut++) {
            CsmMessage.Reader r = new CsmMessage.Reader();
            List<CsmMessage> got = new ArrayList<>(r.feed(stream, 0, cut));
            got.addAll(r.feed(stream, cut, stream.length - cut));
            assertStream("cut at " + cut, got, large);
            assertEquals(0, r.pending());
        }
        CsmMessage.Reader bytewise = new CsmMessage.Reader();
        List<CsmMessage> got = new ArrayList<>();
        for (int i = 0; i < stream.length; i++) got.addAll(bytewise.feed(stream, i, 1));
        assertStream("byte by byte", got, large);
        Random random = new Random(2);
        for (int round = 0; round < 200; round++) {
            CsmMessage.Reader r = new CsmMessage.Reader();
            got.clear();
            for (int off = 0; off < stream.length; ) {
                int n = Math.min(stream.length - off, 1 + random.nextInt(300));
                got.addAll(r.feed(stream, off, n));
                off += n;
            }
            assertStream("random chunks, round " + round, got, large);
        }
    }

    @Test
    public void readerCarriesTheLargestMessage() throws Exception {
        byte[] max = new CsmMessage(0x1D01, new Parameter(3, new byte[CsmMessage.MAX_PARAMETER_VALUE])).encode();
        CsmMessage.Reader r = new CsmMessage.Reader();
        assertEquals(0, r.feed(max, 0, max.length - 1).size());
        assertEquals(max.length - 1, r.pending());
        List<CsmMessage> got = r.feed(max, max.length - 1, 1);
        assertEquals(1, got.size());
        assertEquals(CsmMessage.MAX_PARAMETER_VALUE, got.get(0).single(3).length);
    }

    @Test
    public void readerFailsAsSoonAsFramingIsWrong() throws Exception {
        CsmMessage.Reader r = new CsmMessage.Reader();
        r.feed(new byte[]{0x40}, 0, 1);
        expectProtocol(r, new byte[]{0x41});
        CsmMessage.Reader shortLength = new CsmMessage.Reader();
        shortLength.feed(hex("40400005"), 0, 4);
        expectProtocol(shortLength, hex("1D00"));
        expectProtocol(new CsmMessage.Reader(), hex("4040000A" + "AA02" + "0002" + "0000"));
    }

    private static void assertStream(String what, List<CsmMessage> got, byte[] large) throws ProtocolException {
        assertEquals(what, 3, got.size());
        assertEquals(what, 0x1D00, got.get(0).id);
        assertEquals(what, 32, got.get(1).single(0).length);
        assertEquals(what, 2, got.get(2).parameters().size());
        assertArrayEquals(what, large, got.get(2).parameters().get(0).value());
    }

    private static void expectProtocol(CsmMessage.Reader r, byte[] b) {
        try {
            r.feed(b, 0, b.length);
            fail("accepted " + Arrays.toString(b));
        } catch (ProtocolException expected) {
            // framing error seen at once
        }
    }

    private static void expectIllegal(Runnable r) {
        try {
            r.run();
            fail("accepted");
        } catch (IllegalArgumentException expected) {
            // outside the 16-bit fields
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
