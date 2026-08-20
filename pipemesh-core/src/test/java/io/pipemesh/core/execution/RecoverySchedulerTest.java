package io.pipemesh.core.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoverySchedulerTest {

    /** Counts passes, and can be told to fail. */
    private static final class CountingPass implements RecoveryScheduler.RecoveryPass {

        final AtomicInteger passes = new AtomicInteger();
        private final CountDownLatch swept;
        volatile boolean broken;

        CountingPass(CountDownLatch swept) {
            this.swept = swept;
        }

        @Override
        public int sweep() {
            passes.incrementAndGet();
            swept.countDown();
            if (broken) {
                throw new IllegalStateException("the database is unreachable");
            }
            return 0;
        }
    }

    @Test
    void keepsSweeping() throws Exception {
        CountDownLatch swept = new CountDownLatch(3);
        CountingPass pass = new CountingPass(swept);

        try (RecoveryScheduler scheduler =
                     new RecoveryScheduler(pass, Duration.ofMillis(20), failure -> {
                     }).start()) {

            assertTrue(swept.await(5, TimeUnit.SECONDS), "the schedule should keep running");
        }
    }

    @Test
    void aFailedSweepDoesNotEndTheSchedule() throws Exception {
        CountDownLatch swept = new CountDownLatch(3);
        CountingPass pass = new CountingPass(swept);
        pass.broken = true;
        List<Throwable> failures = new ArrayList<>();

        try (RecoveryScheduler scheduler =
                     new RecoveryScheduler(pass, Duration.ofMillis(20), failures::add).start()) {

            assertTrue(swept.await(5, TimeUnit.SECONDS),
                    "a database that was briefly unreachable must not stop recovery for good");
            assertTrue(failures.size() >= 1, "and the failure should be reported, not swallowed");
        }
    }

    @Test
    void stopsWhenClosed() throws Exception {
        CountDownLatch swept = new CountDownLatch(1);
        CountingPass pass = new CountingPass(swept);

        RecoveryScheduler scheduler =
                new RecoveryScheduler(pass, Duration.ofMillis(20), failure -> {
                }).start();
        assertTrue(swept.await(5, TimeUnit.SECONDS));
        scheduler.close();

        int afterClose = pass.passes.get();
        Thread.sleep(150);

        assertEquals(afterClose, pass.passes.get());
    }

    @Test
    void refusesAnIntervalThatIsNotAnInterval() {
        CountingPass pass = new CountingPass(new CountDownLatch(1));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecoveryScheduler(pass, Duration.ZERO, failure -> {
                }));
    }
}
