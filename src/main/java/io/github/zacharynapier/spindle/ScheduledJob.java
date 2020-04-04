package io.github.zacharynapier.spindle;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * A handle to a job registered with {@link Spindle}.
 *
 * <p>Runs of the same job never overlap. If a run lasts past the next
 * fire time, the missed fires are skipped and the job resumes at the first fire after it ends.
 */
public final class ScheduledJob {

    private final Spindle owner;
    private final CronExpression cron;
    private final Job job;

    private volatile ZonedDateTime nextFireTime;
    private volatile ScheduledFuture<?> pending;
    private volatile boolean cancelled;

    ScheduledJob(Spindle owner, CronExpression cron, Job job) {
        this.owner = owner;
        this.cron = cron;
        this.job = job;
    }

    public CronExpression cron() {
        return cron;
    }

    /** The time of the upcoming (or currently running) fire, or empty once cancelled. */
    public Optional<ZonedDateTime> nextFireTime() {
        return cancelled ? Optional.empty() : Optional.ofNullable(nextFireTime);
    }

    /** Stops future fires. A run already in progress is allowed to finish. */
    public void cancel() {
        cancelled = true;
        ScheduledFuture<?> future = pending;
        if (future != null) {
            future.cancel(false);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "ScheduledJob[" + cron + "]";
    }

    void scheduleAfter(ZonedDateTime after) {
        if (cancelled) {
            return;
        }
        cron.next(after).ifPresent(next -> {
            nextFireTime = next;
            pending = owner.submit(this::run, Duration.between(owner.now(), next));
        });
    }

    private void run() {
        if (cancelled) {
            return;
        }
        try {
            job.run();
        } catch (Exception e) {
            owner.reportFailure(this, e);
        }
        ZonedDateTime now = owner.now();
        scheduleAfter(now.isAfter(nextFireTime) ? now : nextFireTime);
    }
}
