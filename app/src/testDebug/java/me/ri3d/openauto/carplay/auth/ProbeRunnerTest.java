package me.ri3d.openauto.carplay.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ownership of the probe's file operations across activity instances: one at a time, a cancelled
 * operation stopped before the next one starts, and no result for an instance that is gone. The
 * worker is a real thread; the main thread is a queue this test drains, so every step is explicit.
 */
public class ProbeRunnerTest {
    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final ProbeRunner runner = new ProbeRunner(worker, main::add, trace::add);
    private final List<String> results = new ArrayList<>();

    @After
    public void stop() {
        worker.shutdownNow();
    }

    /** Instances A and B run the probe and instance C deletes the fixtures while A is still running. */
    @Test
    public void operationsOfDifferentInstancesNeverOverlap() throws Exception {
        AtomicInteger active = new AtomicInteger(), mostActive = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch releaseA = new CountDownLatch(1);
        runner.submit("run for A", fileOperation("run A", active, mostActive, order, releaseA), results::add);
        runner.submit("run for B", fileOperation("run B", active, mostActive, order, null), results::add);
        runner.submit("delete for C", fileOperation("delete C", active, mostActive, order, null), results::add);
        assertEquals("all three waiting or running", 3, runner.pending());
        releaseA.countDown();
        settle();
        assertEquals("never two at once", 1, mostActive.get());
        assertEquals(Arrays.asList("run A start", "run A end", "run B start", "run B end", "delete C start", "delete C end"), order);
        assertEquals(Arrays.asList("run A", "run B", "delete C"), results);
        assertEquals(0, runner.pending());
    }

    /** Back, or a recreated activity: the old instance cancels, and the new one's work starts only after the old work returned. */
    @Test
    public void cancelledOperationHasStoppedBeforeTheNextStarts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean firstReturned = new AtomicBoolean(), nextSawItReturned = new AtomicBoolean();
        ProbeRunner.Ticket old = runner.submit("run for the old instance", cancel -> {
            started.countDown();
            try {
                for (int step = 0; step < 100_000; step++) {
                    if (cancel.cancelled()) throw new CancellationException("probe cancelled");
                    Thread.sleep(1); // one step on the fixture files
                }
                return "old report";
            } finally {
                firstReturned.set(true);
            }
        }, results::add);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        runner.cancel(old); // onDestroy
        runner.submit("run for the new instance", cancel -> {
            nextSawItReturned.set(firstReturned.get());
            return "new report";
        }, results::add);
        settle();
        assertTrue("the old operation had returned before the new one began", nextSawItReturned.get());
        assertEquals("only the live instance gets a report", Collections.singletonList("new report"), results);
        assertTrue(trace.toString(), trace.contains("#1 run for the old instance stopped after cancellation"));
    }

    @Test
    public void operationCancelledBeforeItStartsNeverRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        runner.submit("run for A", cancel -> {
            release.await(5, TimeUnit.SECONDS);
            return "A";
        }, results::add);
        ProbeRunner.Ticket queued = runner.submit("delete for B", cancel -> {
            ran.set(true);
            return "B";
        }, results::add);
        runner.cancel(queued);
        release.countDown();
        settle();
        assertFalse(ran.get());
        assertEquals(Collections.singletonList("A"), results);
        assertTrue(trace.toString(), trace.contains("#2 delete for B cancelled before it started"));
    }

    /** The operation finished, but its instance went away before the main thread delivered the result. */
    @Test
    public void staleResultIsDroppedAndALiveOneDelivered() throws Exception {
        ProbeRunner.Ticket gone = runner.submit("run for A", cancel -> "report A", results::add);
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS); // A has finished; its result waits on the main thread
        assertEquals(1, main.size());
        runner.cancel(gone);
        drainMain();
        assertTrue("nothing reaches a destroyed instance", results.isEmpty());
        assertTrue(trace.toString(), trace.contains("#1 run for A result dropped: its activity is gone"));

        runner.submit("run for B", cancel -> "report B", results::add);
        settle();
        assertEquals(Collections.singletonList("report B"), results);
    }

    private ProbeRunner.Operation fileOperation(String name, AtomicInteger active, AtomicInteger mostActive, List<String> order, CountDownLatch gate) {
        return cancel -> {
            int now = active.incrementAndGet();
            mostActive.set(Math.max(mostActive.get(), now));
            order.add(name + " start");
            if (gate != null) assertTrue(gate.await(5, TimeUnit.SECONDS));
            Thread.sleep(5);
            order.add(name + " end");
            active.decrementAndGet();
            return name;
        };
    }

    /** Waits until the worker has nothing left, then runs what it handed to the main thread. */
    private void settle() throws Exception {
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        drainMain();
    }

    private void drainMain() {
        for (Runnable r; (r = main.poll()) != null; ) r.run();
    }
}
