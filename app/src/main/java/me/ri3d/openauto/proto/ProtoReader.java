package me.ri3d.openauto.proto;

import java.nio.charset.Charset;

/**
 * Minimal protobuf wire-format decoder over a byte range. Callers iterate {@link #next()} and switch
 * on {@link #field()}; unknown fields are skipped with {@link #skip()}. Every bounds violation throws
 * {@link ProtoException} so a malformed or truncated message from the phone is rejected, never
 * read past its end.
 */
public final class ProtoReader {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private byte[] buf;
    private int pos, end;
    private int field, wire;

    public ProtoReader() {}

    public ProtoReader(byte[] buf, int off, int len) {
        reset(buf, off, len);
    }

    public ProtoReader reset(byte[] buf, int off, int len) {
        if (off < 0 || len < 0 || off + len > buf.length) throw new ProtoException("range outside buffer");
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
        return this;
    }

    public boolean hasMore() {
        return pos < end;
    }

    /** Reads the next tag. Returns false at the end of the range. */
    public boolean next() {
        if (pos >= end) return false;
        long t = varint();
        field = (int) (t >>> 3);
        wire = (int) (t & 7);
        if (field == 0) throw new ProtoException("field number 0");
        return true;
    }

    public int field() {
        return field;
    }

    /** Reads the next tag and requires it to be {@code expected}; for fixed-layout test and reply parsing. */
    public ProtoReader nextField(int expected) {
        if (!next()) throw new ProtoException("expected field " + expected + ", found end of message");
        if (field != expected) throw new ProtoException("expected field " + expected + ", found " + field);
        return this;
    }

    public int wireType() {
        return wire;
    }

    public long varint() {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos >= end) throw new ProtoException("truncated varint");
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 64) throw new ProtoException("varint too long");
        }
    }

    public int int32() {
        requireWire(0);
        return (int) varint();
    }

    public long int64() {
        requireWire(0);
        return varint();
    }

    public boolean bool() {
        requireWire(0);
        return varint() != 0;
    }

    public long fixed64() {
        requireWire(1);
        if (end - pos < 8) throw new ProtoException("truncated fixed64");
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (long) (buf[pos++] & 0xFF) << (8 * i);
        return v;
    }

    /** Length-delimited field: returns its length and positions the reader at its first byte. */
    public int lengthDelimited() {
        requireWire(2);
        long len = varint();
        if (len < 0 || len > end - pos) throw new ProtoException("length-delimited field exceeds message");
        return (int) len;
    }

    public String string() {
        int len = lengthDelimited();
        String s = new String(buf, pos, len, UTF8);
        pos += len;
        return s;
    }

    public byte[] bytes() {
        int len = lengthDelimited();
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    /** Positions a fresh reader over an embedded message and advances past it. */
    public ProtoReader message(ProtoReader sub) {
        int len = lengthDelimited();
        sub.reset(buf, pos, len);
        pos += len;
        return sub;
    }

    public void skip() {
        switch (wire) {
            case 0: varint(); break;
            case 1: if (end - pos < 8) throw new ProtoException("truncated fixed64"); pos += 8; break;
            case 2: { int len = lengthDelimited(); pos += len; break; } // not "pos += f()": f() moves pos
            case 5: if (end - pos < 4) throw new ProtoException("truncated fixed32"); pos += 4; break;
            default: throw new ProtoException("unsupported wire type " + wire);
        }
    }

    private void requireWire(int w) {
        if (wire != w) throw new ProtoException("field " + field + ": wire type " + wire + ", expected " + w);
    }

    public int position() {
        return pos;
    }

    public byte[] buffer() {
        return buf;
    }

    public static final class ProtoException extends RuntimeException {
        public ProtoException(String msg) {
            super(msg);
        }
    }
}
