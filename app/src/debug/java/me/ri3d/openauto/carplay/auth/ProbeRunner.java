package me.ri3d.openauto.carplay.auth;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a debug screen's operations one at a time on a single worker: for the MFi probe its file
 * operations (a probe run, deleting the fixtures), so no two activity instances ever use the
 * fixture directory at the same time; for the transport check its device reads and RFCOMM tests.
 *
 * <p>Each operation belongs to the activity instance that asked for it. That instance cancels it
 * when it goes away ({@code onDestroy}, which exists on every API level, unlike
 * {@code isDestroyed()}): an operation that has not started never starts, a running one stops at
 * its next step, and the result of either is dropped instead of reaching a dead screen. Because the
 * worker takes the next operation only after the previous one has returned, a cancelled operation
 * has stopped using the files before anything else touches them.
 *
 * <p>Results are handed to the UI executor (the main thread on Android); {@link #submit} and
 * {@link #cancel} are called on that same thread, so a cancellation and a result never race.
 */
public final class ProbeRunner {
    /** Checked by an operation between its steps. */
    public interface Cancel {
        boolean cancelled();
    }

    public interface Operation {
        String run(Cancel cancel) throws Exception;
    }

    public interface Listener {
        void onResult(String text);
    }

    /** Where progress lines go: logcat on Android. */
    public interface Trace {
        void line(String text);
    }

    /** One submitted operation and its owner's listener. */
    public static final class Ticket implements Cancel {
        final int id;
        final String name;
        private volatile boolean cancelled;
        private Listener listener;

        Ticket(int id, String name, Listener listener) {
            this.id = id;
            this.name = name;
            this.listener = listener;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public String toString() {
            return "#" + id + " " + name;
        }
    }

    private final Executor worker, ui;
    private final Trace trace;
    private final AtomicInteger pending = new AtomicInteger();
    private int nextId;

    /** @param worker must run one task at a time, in order */
    public ProbeRunner(Executor worker, Executor ui, Trace trace) {
        this.worker = worker;
        this.ui = ui;
        this.trace = trace;
    }

    /** On the UI thread. */
    public Ticket submit(String name, final Operation op, Listener listener) {
        final Ticket t = new Ticket(++nextId, name, listener);
        int ahead = pending.getAndIncrement();
        trace.line(t + " queued" + (ahead > 0 ? " behind " + ahead + " other operation(s)" : ""));
        worker.execute(() -> {
            String result;
            try {
                if (t.cancelled) {
                    trace.line(t + " cancelled before it started");
                    return;
                }
                trace.line(t + " started");
                result = op.run(t);
                trace.line(t + (t.cancelled ? " returned after cancellation" : " finished"));
            } catch (CancellationException e) {
                trace.line(t + " stopped after cancellation");
                return;
            } catch (Throwable e) {
                result = "ERROR " + e;
                trace.line(t + " failed: " + e);
            } finally {
                pending.decrementAndGet();
            }
            final String text = result;
            ui.execute(() -> {
                if (t.cancelled) {
                    trace.line(t + " result dropped: its activity is gone");
                    return;
                }
                t.listener.onResult(text);
            });
        });
        return t;
    }

    /** On the UI thread, from the owner's {@code onDestroy}. */
    public void cancel(Ticket t) {
        if (t.cancelled) return;
        t.cancelled = true;
        t.listener = null;
        trace.line(t + " cancelled by its owner");
    }

    /** Operations queued or running, the caller's own included once submitted. */
    public int pending() {
        return pending.get();
    }
}
