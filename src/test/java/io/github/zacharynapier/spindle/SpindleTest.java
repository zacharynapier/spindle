package io.github.zacharynapier.spindle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpindleTest {

    // 100ms before a minute boundary, so "* * * * *" fires almost immediately.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2020-05-23T19:59:59.900Z"), ZoneOffset.UTC);

    @Test
    void retriesUntilTheJobSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch succeeded = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();

        try (Spindle spindle = Spindle.builder().clock(CLOCK).onFailure((job, e) -> failure.set(e)).build()) {
            spindle.schedule("* * * * *", () -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("not yet");
                }
                succeeded.countDown();
            }, RetryPolicy.fixed(3, Duration.ofMillis(10)));

            assertTrue(succeeded.await(5, TimeUnit.SECONDS));
        }
        assertEquals(3, attempts.get());
        assertNull(failure.get());
    }

    @Test
    void reportsFailureAfterTheLastAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<ScheduledJob> failedJob = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Exception boom = new Exception("boom");

        Spindle spindle = Spindle.builder()
                .clock(CLOCK)
                .retry(RetryPolicy.exponential(2, Duration.ofMillis(10)))
                .onFailure((job, e) -> {
                    failedJob.set(job);
                    failure.set(e);
                    reported.countDown();
                })
                .build();
        try (spindle) {
            ScheduledJob job = spindle.schedule("* * * * *", () -> {
                attempts.incrementAndGet();
                throw boom;
            });

            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertSame(job, failedJob.get());
            assertSame(boom, failure.get());
        }
        assertEquals(2, attempts.get());
    }

    @Test
    void closeWaitsForRunningJobs() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();

        Spindle spindle = Spindle.builder().clock(CLOCK).build();
        spindle.schedule("* * * * *", () -> {
            started.countDown();
            Thread.sleep(200);
            finished.set(true);
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        spindle.close();

        assertTrue(finished.get());
        assertThrows(IllegalStateException.class, () -> spindle.schedule("* * * * *", () -> {}));
    }

    @Test
    void closeInterruptsJobsThatOutlastTheTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        Spindle spindle = Spindle.builder().clock(CLOCK).shutdownTimeout(Duration.ofMillis(50)).build();
        spindle.schedule("* * * * *", () -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        spindle.close();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelledJobHasNoNextFireTime() {
        try (Spindle spindle = Spindle.builder().clock(CLOCK).build()) {
            ScheduledJob job = spindle.schedule("0 12 * * *", () -> {});
            assertEquals(Instant.parse("2020-05-24T12:00:00Z"), job.nextFireTime().orElseThrow().toInstant());

            job.cancel();
            assertTrue(job.isCancelled());
            assertTrue(job.nextFireTime().isEmpty());
        }
    }

    @Test
    void rejectsExpressionsThatNeverFire() {
        try (Spindle spindle = Spindle.builder().clock(CLOCK).build()) {
            assertThrows(IllegalArgumentException.class, () -> spindle.schedule("0 0 31 4 *", () -> {}));
        }
    }
}
