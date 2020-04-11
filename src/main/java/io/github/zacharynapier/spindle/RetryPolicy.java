package io.github.zacharynapier.spindle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How many times a failed run is attempted, and how long to wait between attempts.
 *
 * <p>The delay after attempt {@code n} is {@code initialDelay * multiplier^(n-1)}, capped at
 * {@code maxDelay}. With jitter {@code j}, each delay is then reduced by a random fraction of up
 * to {@code j}, which spreads out retries from jobs that failed together.
 */
public final class RetryPolicy {

    private static final Duration UNBOUNDED = Duration.ofMillis(Long.MAX_VALUE);
    private static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0);

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final double jitter;

    private RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay, double jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException("multiplier must be a finite number >= 1");
        }
        if (!(jitter >= 0.0 && jitter <= 1.0)) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
    }

    /** Runs each fire once; failures are reported and not retried. */
    public static RetryPolicy none() {
        return NONE;
    }

    /** Waits the same delay between every attempt. */
    public static RetryPolicy fixed(int maxAttempts, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        return new RetryPolicy(maxAttempts, delay, 1.0, delay, 0.0);
    }

    /** Doubles the delay after each failed attempt, with no upper bound until {@link #withMaxDelay}. */
    public static RetryPolicy exponential(int maxAttempts, Duration initialDelay) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        return new RetryPolicy(maxAttempts, initialDelay, 2.0, UNBOUNDED, 0.0);
    }

    public RetryPolicy withMultiplier(double multiplier) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter);
    }

    public RetryPolicy withMaxDelay(Duration maxDelay) {
        Objects.requireNonNull(maxDelay, "maxDelay");
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter);
    }

    /** @param jitter fraction between 0 and 1 by which each delay may be randomly shortened */
    public RetryPolicy withJitter(double jitter) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitter);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns the delay before the attempt that follows failed attempt {@code attempt} (1-based). */
    public Duration delayAfter(int attempt) {
        return delayAfter(attempt, ThreadLocalRandom.current().nextDouble());
    }

    Duration delayAfter(int attempt, double random) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        millis = Math.min(millis, maxDelay.toMillis());
        millis -= millis * jitter * random;
        return Duration.ofMillis(Math.round(millis));
    }
}
