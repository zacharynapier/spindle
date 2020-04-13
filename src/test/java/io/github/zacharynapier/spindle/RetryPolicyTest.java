package io.github.zacharynapier.spindle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void noneMakesASingleAttempt() {
        assertEquals(1, RetryPolicy.none().maxAttempts());
    }

    @Test
    void exponentialDoublesEachDelay() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofMillis(100));

        assertEquals(Duration.ofMillis(100), policy.delayAfter(1));
        assertEquals(Duration.ofMillis(200), policy.delayAfter(2));
        assertEquals(Duration.ofMillis(400), policy.delayAfter(3));
        assertEquals(Duration.ofMillis(800), policy.delayAfter(4));
    }

    @Test
    void customMultiplier() {
        RetryPolicy policy = RetryPolicy.exponential(4, Duration.ofSeconds(1)).withMultiplier(1.5);

        assertEquals(Duration.ofMillis(1000), policy.delayAfter(1));
        assertEquals(Duration.ofMillis(1500), policy.delayAfter(2));
        assertEquals(Duration.ofMillis(2250), policy.delayAfter(3));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        RetryPolicy policy = RetryPolicy.exponential(10, Duration.ofSeconds(1)).withMaxDelay(Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(4), policy.delayAfter(3));
        assertEquals(Duration.ofSeconds(5), policy.delayAfter(4));
        assertEquals(Duration.ofSeconds(5), policy.delayAfter(9));
    }

    @Test
    void largeAttemptCountsDoNotOverflow() {
        RetryPolicy capped = RetryPolicy.exponential(5000, Duration.ofSeconds(1)).withMaxDelay(Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(1), capped.delayAfter(4000));

        RetryPolicy uncapped = RetryPolicy.exponential(5000, Duration.ofSeconds(1));
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), uncapped.delayAfter(4000));
    }

    @Test
    void fixedUsesTheSameDelay() {
        RetryPolicy policy = RetryPolicy.fixed(3, Duration.ofSeconds(2));

        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(1));
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(2));
    }

    @Test
    void jitterShortensDelayByUpToTheGivenFraction() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(1)).withJitter(0.25);

        assertEquals(Duration.ofMillis(2000), policy.delayAfter(2, 0.0));
        assertEquals(Duration.ofMillis(1750), policy.delayAfter(2, 0.5));
        assertEquals(Duration.ofMillis(1500), policy.delayAfter(2, 1.0));
    }

    @Test
    void jitterIsAppliedAfterTheCap() {
        RetryPolicy policy = RetryPolicy.exponential(10, Duration.ofSeconds(1))
                .withMaxDelay(Duration.ofSeconds(10))
                .withJitter(0.5);

        assertEquals(Duration.ofSeconds(5), policy.delayAfter(8, 1.0));
    }

    @Test
    void randomJitterStaysInRange() {
        RetryPolicy policy = RetryPolicy.exponential(5, Duration.ofSeconds(1)).withJitter(0.2);

        for (int i = 0; i < 1000; i++) {
            long millis = policy.delayAfter(3).toMillis();
            assertTrue(millis >= 3200 && millis <= 4000, "delay out of range: " + millis);
        }
    }

    @Test
    void rejectsInvalidSettings() {
        Duration second = Duration.ofSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.exponential(0, second));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.fixed(3, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.exponential(3, second).withMultiplier(0.5));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.exponential(3, second).withMultiplier(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.exponential(3, second).withJitter(1.5));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.exponential(3, second).delayAfter(0));
    }
}
