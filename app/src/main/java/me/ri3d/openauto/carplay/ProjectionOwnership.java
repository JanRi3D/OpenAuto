package me.ri3d.openauto.carplay;

/** Serializes device media ownership across the two projection protocols. */
public final class ProjectionOwnership {
    private static String owner;
    private ProjectionOwnership() { }
    public static synchronized boolean acquire(String candidate) {
        if (owner != null && !owner.equals(candidate)) return false;
        owner = candidate;
        return true;
    }
    public static synchronized void release(String candidate) {
        if (candidate.equals(owner)) owner = null;
    }
    public static synchronized String owner() { return owner; }
}
