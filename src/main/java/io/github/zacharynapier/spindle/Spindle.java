package io.github.zacharynapier.spindle;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Runs jobs on cron schedules using a fixed pool of threads.
 *
 * <pre>{@code
 * Spindle spindle = Spindle.builder()
 *         .threads(2)
 *         .build();
 *
 * spindle.schedule("0 * * * *", () -> report.refresh());
 * }</pre>
 */
public final class Spindle {

    private static final System.Logger LOG = System.getLogger(Spindle.class.getName());

    private final ScheduledThreadPoolExecutor executor;
    private final Clock clock;
    private final BiConsumer<ScheduledJob, Exception> onFailure;

    private Spindle(Builder builder) {
        this.executor = new ScheduledThreadPoolExecutor(builder.threads, new NamedThreadFactory());
        this.executor.setRemoveOnCancelPolicy(true);
        this.clock = builder.clock;
        this.onFailure = builder.onFailure;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Schedules {@code job} to run at each time matching {@code cron}.
     *
     * @throws IllegalArgumentException if the expression is malformed or can never fire
     * @throws IllegalStateException if the scheduler has been shut down
     */
    public ScheduledJob schedule(String cron, Job job) {
        Objects.requireNonNull(job, "job");
        CronExpression expression = CronExpression.parse(cron);
        if (executor.isShutdown()) {
            throw new IllegalStateException("Scheduler has been shut down");
        }
        ZonedDateTime now = now();
        if (expression.next(now).isEmpty()) {
            throw new IllegalArgumentException("Cron expression '" + cron + "' never fires");
        }
        ScheduledJob scheduled = new ScheduledJob(this, expression, job);
        scheduled.scheduleAfter(now);
        return scheduled;
    }

    /** Stops accepting new jobs. Runs already in progress continue; this does not wait for them. */
    public void shutdown() {
        executor.shutdown();
    }

    /** Stops new fires and interrupts running jobs. */
    public void shutdownNow() {
        executor.shutdownNow();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Blocks until all running jobs finish after a shutdown, or the timeout elapses. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    ScheduledFuture<?> submit(Runnable task, Duration delay) {
        return executor.schedule(task, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
    }

    void reportFailure(ScheduledJob job, Exception failure) {
        try {
            onFailure.accept(job, failure);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Failure handler threw for " + job, e);
        }
    }

    public static final class Builder {

        private int threads = 1;
        private Clock clock = Clock.systemDefaultZone();
        private BiConsumer<ScheduledJob, Exception> onFailure =
                (job, e) -> LOG.log(System.Logger.Level.WARNING, job + " failed", e);

        private Builder() {
        }

        /** Number of jobs that may run at the same time. Defaults to 1. */
        public Builder threads(int threads) {
            if (threads < 1) {
                throw new IllegalArgumentException("threads must be at least 1");
            }
            this.threads = threads;
            return this;
        }

        /** Zone in which cron expressions are evaluated. Defaults to the system zone. */
        public Builder zone(ZoneId zone) {
            this.clock = clock.withZone(Objects.requireNonNull(zone, "zone"));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Called when a run throws. Defaults to logging a warning. */
        public Builder onFailure(BiConsumer<ScheduledJob, Exception> onFailure) {
            this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
            return this;
        }

        public Spindle build() {
            return new Spindle(this);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private static final AtomicInteger COUNT = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task, "spindle-" + COUNT.incrementAndGet());
        }
    }
}
