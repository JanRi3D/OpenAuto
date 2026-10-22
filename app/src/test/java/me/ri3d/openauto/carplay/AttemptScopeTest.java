package me.ri3d.openauto.carplay;

import org.junit.Test;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AttemptScopeTest {
    @Test public void completedResourcesCanBeForgottenWithoutRetainingOrClosingThemAgain() {
        AttemptScope attempt = new AttemptScope();
        AtomicInteger closes = new AtomicInteger();
        Closeable resource = closes::incrementAndGet;
        attempt.own(resource);
        attempt.forget(resource);
        attempt.close();
        assertEquals(0, closes.get());
    }
    @Test public void closesEachOwnedResourceOnceAndRejectsLateResources() throws Exception {
        AttemptScope attempt = new AttemptScope();
        AtomicInteger closes = new AtomicInteger();
        Closeable first = closes::incrementAndGet;
        attempt.own(first);
        assertTrue(attempt.isOpen());
        attempt.close();
        attempt.close();
        assertFalse(attempt.isOpen());
        assertEquals(1, closes.get());
        attempt.own(closes::incrementAndGet);
        assertEquals(2, closes.get());
    }

    @Test public void oneBrokenCloseDoesNotLeakOtherResources() {
        AttemptScope attempt = new AttemptScope();
        AtomicInteger closes = new AtomicInteger();
        attempt.own(closes::incrementAndGet);
        attempt.own(() -> { throw new java.io.IOException("peer disappeared"); });
        attempt.close();
        assertEquals(1, closes.get());
    }
}
