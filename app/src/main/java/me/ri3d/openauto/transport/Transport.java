package me.ri3d.openauto.transport;

import java.io.Closeable;
import java.io.IOException;

/**
 * A byte stream to the phone. Reads fill {@code buf} from offset 0 (the API 16 USB bulk API cannot
 * read at an offset); writes send {@code len} bytes from offset 0. {@link #close()} must unblock a
 * pending read from another thread.
 */
public interface Transport extends Closeable {
    /** Returns bytes read (>0), or -1 when the peer is gone. Blocks. */
    int read(byte[] buf, int maxLen) throws IOException;

    void write(byte[] buf, int len) throws IOException;

    /** Short human-readable description for the status UI, e.g. "USB", "TCP 192.168.43.1:5277". */
    String describe();
}
