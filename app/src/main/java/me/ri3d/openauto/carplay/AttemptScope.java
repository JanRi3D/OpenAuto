package me.ri3d.openauto.carplay;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Owns blocking resources for one attempt; resources arriving after cancellation close immediately. */
public final class AttemptScope implements Closeable {
    private final IdentityHashMap<Closeable, Boolean> resources = new IdentityHashMap<>();
    private boolean open = true;

    public synchronized boolean isOpen() { return open; }

    /** The caller has completed/closed this resource and no longer needs cancellation ownership. */
    public synchronized void forget(Closeable resource) { resources.remove(resource); }

    public <T extends Closeable> T own(T resource) {
        boolean keep;
        synchronized (this) {
            keep = open;
            if (keep) resources.put(resource, Boolean.TRUE);
        }
        if (!keep) closeQuietly(resource);
        return resource;
    }

    @Override public void close() {
        List<Closeable> closing;
        synchronized (this) {
            if (!open) return;
            open = false;
            closing = new ArrayList<>(resources.keySet());
            resources.clear();
        }
        for (Closeable resource : closing) closeQuietly(resource);
    }

    private static void closeQuietly(Closeable resource) {
        try { resource.close(); } catch (Exception ignored) { }
    }
}
