package com.aliothmoon.maameow.root;
import android.app.Application;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ProcessExitTest {
    private final CopyOnWriteArrayList<Integer> codes = new CopyOnWriteArrayList<>();
    @Before public void setup() {
        ProcessExit.resetForTest(); ProcessExit.halt = codes::add; ProcessExit.deadlineMs = 1000;
    }
    @After public void reset() {
        ProcessExit.resetForTest(); ProcessExit.deadlineMs = ProcessExit.DEADLINE_MS;
        ProcessExit.halt = code -> { throw new AssertionError("unexpected halt " + code); };
    }
    @Test public void normalCleanupFinishesBeforeExit() {
        AtomicInteger cleaned = new AtomicInteger();
        ProcessExit.exit("normal", () -> { assertTrue(codes.isEmpty()); cleaned.incrementAndGet(); });
        assertEquals(1, cleaned.get()); assertEquals(java.util.List.of(0), codes);
    }
    @Test public void cleanupFailureStillExits() {
        ProcessExit.exit("failure", () -> { throw new IllegalStateException("cleanup"); });
        assertEquals(java.util.List.of(0), codes);
    }
    @Test public void nestedCleanupRunsBeforeSingleExit() {
        AtomicInteger cleaned = new AtomicInteger();
        ProcessExit.exit("outer", () -> ProcessExit.exit("nested", cleaned::incrementAndGet));
        assertEquals(1, cleaned.get()); assertEquals(java.util.List.of(0), codes);
    }
    @Test public void blockedCleanupCannotPreventDeadlineOrRunCompetingCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), halted = new CountDownLatch(1);
        ProcessExit.deadlineMs = 50;
        ProcessExit.halt = code -> { codes.add(code); halted.countDown(); };
        Thread worker = new Thread(() -> ProcessExit.exit("blocked", () -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        }));
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            AtomicInteger competing = new AtomicInteger();
            ProcessExit.exit("competing", competing::incrementAndGet);
            assertEquals(0, competing.get());
            assertTrue(halted.await(2, TimeUnit.SECONDS)); assertEquals(java.util.List.of(1), codes);
        } finally { release.countDown(); worker.join(2000); }
        assertFalse(worker.isAlive());
    }
}
