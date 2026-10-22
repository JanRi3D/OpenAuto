package me.ri3d.openauto.carplay.iap2;

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One iAP2 control session message: start bytes 0x4040, a big-endian unsigned 16-bit length that
 * counts this 6-byte header, a 16-bit message ID, then parameters. Each parameter is a 16-bit length
 * that counts its own 4-byte header, a 16-bit parameter ID and the value. Parameters keep their order
 * and an ID may repeat. A group parameter's value is itself a parameter list, read and written with
 * {@link #decodeParameters} and {@link #encodeParameters}.
 */
public final class CsmMessage {
    public static final int START = 0x4040;
    public static final int HEADER_LENGTH = 6;
    public static final int PARAMETER_HEADER_LENGTH = 4;
    public static final int MAX_LENGTH = 0xFFFF;
    /** The longest value a single parameter of a message can carry. */
    public static final int MAX_PARAMETER_VALUE = MAX_LENGTH - HEADER_LENGTH - PARAMETER_HEADER_LENGTH;

    public final int id;
    private final List<Parameter> parameters;

    public CsmMessage(int id, List<Parameter> parameters) {
        this.id = u16Checked(id, "message ID");
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public CsmMessage(int id, Parameter... parameters) {
        this(id, Arrays.asList(parameters));
    }

    /** In order, repeated IDs included. */
    public List<Parameter> parameters() {
        return parameters;
    }

    /** The value of the only parameter with this ID, or null if there is none. */
    public byte[] single(int parameterId) throws ProtocolException {
        byte[] found = null;
        for (Parameter p : parameters) {
            if (p.id != parameterId) continue;
            if (found != null) throw new ProtocolException(String.format(Locale.US, "message 0x%04X repeats parameter %d", id, parameterId));
            found = p.value();
        }
        return found;
    }

    /** @throws IllegalArgumentException if the message would exceed {@link #MAX_LENGTH} bytes */
    public byte[] encode() {
        byte[] body = encodeParameters(parameters);
        int length = HEADER_LENGTH + body.length;
        if (length > MAX_LENGTH) throw new IllegalArgumentException("message of " + length + " bytes exceeds " + MAX_LENGTH);
        byte[] out = new byte[length];
        putU16(out, 0, START);
        putU16(out, 2, length);
        putU16(out, 4, id);
        System.arraycopy(body, 0, out, HEADER_LENGTH, body.length);
        return out;
    }

    /** Exactly one message filling {@code len} bytes. */
    public static CsmMessage decode(byte[] b, int off, int len) throws ProtocolException {
        if (len < HEADER_LENGTH) throw new ProtocolException("message of " + len + " bytes is shorter than its header");
        if (u16(b, off) != START) throw new ProtocolException(String.format(Locale.US, "start bytes 0x%04X, expected 0x4040", u16(b, off)));
        if (u16(b, off + 2) != len) throw new ProtocolException("length field says " + u16(b, off + 2) + " bytes, message has " + len);
        return new CsmMessage(u16(b, off + 4), decodeParameters(b, off + HEADER_LENGTH, len - HEADER_LENGTH));
    }

    public static byte[] encodeParameters(List<Parameter> parameters) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[PARAMETER_HEADER_LENGTH];
        for (Parameter p : parameters) {
            putU16(header, 0, PARAMETER_HEADER_LENGTH + p.value.length);
            putU16(header, 2, p.id);
            out.write(header, 0, header.length);
            out.write(p.value, 0, p.value.length);
        }
        return out.toByteArray();
    }

    /** A parameter list that fills {@code len} bytes exactly. */
    public static List<Parameter> decodeParameters(byte[] b, int off, int len) throws ProtocolException {
        List<Parameter> out = new ArrayList<>();
        int end = off + len;
        while (off < end) {
            if (end - off < PARAMETER_HEADER_LENGTH) throw new ProtocolException((end - off) + " bytes left over after the last parameter");
            int length = u16(b, off);
            if (length < PARAMETER_HEADER_LENGTH || length > end - off) {
                throw new ProtocolException("parameter " + u16(b, off + 2) + " claims " + length + " bytes, " + (end - off) + " remain");
            }
            out.add(new Parameter(u16(b, off + 2), Arrays.copyOfRange(b, off + PARAMETER_HEADER_LENGTH, off + length)));
            off += length;
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "0x%04X", id));
        for (Parameter p : parameters) sb.append(" [").append(p.id).append(": ").append(p.value.length).append(" B]");
        return sb.toString();
    }

    public static final class Parameter {
        public final int id;
        private final byte[] value;

        public Parameter(int id, byte[] value) {
            this.id = u16Checked(id, "parameter ID");
            if (value.length > MAX_PARAMETER_VALUE) throw new IllegalArgumentException("parameter value of " + value.length + " bytes is too long");
            this.value = value.clone();
        }

        public byte[] value() {
            return value.clone();
        }
    }

    /**
     * Turns control session bytes, split or joined at any point, back into messages. Holds at most
     * one partial message (64 KiB). Invalid start bytes or lengths throw as soon as they are seen;
     * the stream cannot be resynchronised afterwards, so the session ends.
     */
    public static final class Reader {
        private final byte[] buf = new byte[MAX_LENGTH];
        private int have;

        public List<CsmMessage> feed(byte[] b, int off, int len) throws ProtocolException {
            List<CsmMessage> out = new ArrayList<>(1);
            while (len > 0) {
                int want = have < HEADER_LENGTH ? HEADER_LENGTH : u16(buf, 2);
                int n = Math.min(len, want - have);
                System.arraycopy(b, off, buf, have, n);
                have += n;
                off += n;
                len -= n;
                if (have >= 2 && u16(buf, 0) != START) {
                    throw new ProtocolException(String.format(Locale.US, "start bytes 0x%04X, expected 0x4040", u16(buf, 0)));
                }
                if (have == HEADER_LENGTH && u16(buf, 2) < HEADER_LENGTH) {
                    throw new ProtocolException("length field " + u16(buf, 2) + " is shorter than the header");
                }
                if (have >= HEADER_LENGTH && have == u16(buf, 2)) {
                    out.add(decode(buf, 0, have));
                    have = 0;
                }
            }
            return out;
        }

        /** Bytes of an incomplete message waiting for the rest. */
        public int pending() {
            return have;
        }
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static int u16Checked(int v, String what) {
        if (v < 0 || v > 0xFFFF) throw new IllegalArgumentException(what + " " + v + " does not fit 16 bits");
        return v;
    }
}
