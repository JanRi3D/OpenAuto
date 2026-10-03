package me.ri3d.openauto.proto;

import java.nio.charset.Charset;

/**
 * Minimal protobuf wire-format encoder (varint, fixed64, length-delimited). Enough for every message
 * the head unit sends; no code generation and no library. Grows its buffer; reuse one per thread.
 */
public final class ProtoWriter {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private byte[] buf;
    private int pos;

    public ProtoWriter() {
        this(64);
    }

    public ProtoWriter(int capacity) {
        buf = new byte[capacity];
    }

    public ProtoWriter reset() {
        pos = 0;
        return this;
    }

    public int size() {
        return pos;
    }

    /** Copy of the encoded bytes. */
    public byte[] toBytes() {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    public byte[] buffer() {
        return buf;
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            byte[] n = new byte[Math.max(buf.length * 2, pos + extra)];
            System.arraycopy(buf, 0, n, 0, pos);
            buf = n;
        }
    }

    public ProtoWriter raw(byte b) {
        ensure(1);
        buf[pos++] = b;
        return this;
    }

    public ProtoWriter raw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    public ProtoWriter varint(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 1 * 7;
        }
        buf[pos++] = (byte) v;
        return this;
    }

    public ProtoWriter tag(int field, int wireType) {
        return varint(((long) field << 3) | wireType);
    }

    // ---- field writers ------------------------------------------------------------------------

    /** int32/uint32/int64/uint64/enum/bool (negative int32 is sign-extended to 10 bytes, as protobuf does). */
    public ProtoWriter uint(int field, long v) {
        return tag(field, 0).varint(v);
    }

    public ProtoWriter int32(int field, int v) {
        return tag(field, 0).varint((long) v);
    }

    public ProtoWriter bool(int field, boolean v) {
        return tag(field, 0).varint(v ? 1 : 0);
    }

    public ProtoWriter string(int field, String s) {
        byte[] b = s.getBytes(UTF8);
        return bytes(field, b, 0, b.length);
    }

    public ProtoWriter bytes(int field, byte[] b, int off, int len) {
        tag(field, 2).varint(len);
        return raw(b, off, len);
    }

    /** Embedded message: the sub-writer's content becomes a length-delimited field. */
    public ProtoWriter message(int field, ProtoWriter sub) {
        return bytes(field, sub.buf, 0, sub.pos);
    }

    public ProtoWriter fixed64(int field, long v) {
        tag(field, 1);
        ensure(8);
        for (int i = 0; i < 8; i++) buf[pos++] = (byte) (v >>> (8 * i));
        return this;
    }
}
