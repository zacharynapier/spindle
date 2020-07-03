package io.github.zacharynapier.spindle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @ParameterizedTest(name = "{0} after {1} is {2}")
    @CsvSource(delimiter = '|', value = {
            // every minute, always strictly after the given time
            "* * * * *          | 2020-05-23T10:15:30 | 2020-05-23T10:16",
            "* * * * *          | 2020-05-23T10:15:00 | 2020-05-23T10:16",
            "* * * * *          | 2020-12-31T23:59:59 | 2021-01-01T00:00",
            // steps
            "*/15 * * * *       | 2020-05-23T10:16    | 2020-05-23T10:30",
            "*/15 * * * *       | 2020-05-23T10:46    | 2020-05-23T11:00",
            "5/20 * * * *       | 2020-05-23T10:26    | 2020-05-23T10:45",
            "0-30/10 9-17 * * * | 2020-05-23T17:31    | 2020-05-24T09:00",
            // lists and ranges
            "0 8,12,18 * * *    | 2020-05-23T12:00    | 2020-05-23T18:00",
            "30 9-11 * * *      | 2020-05-23T11:30    | 2020-05-24T09:30",
            "0 9 * * 1-5        | 2020-05-22T10:00    | 2020-05-25T09:00",
            // months and years
            "0 0 1 1 *          | 2020-06-01T00:00    | 2021-01-01T00:00",
            "0 0 31 * *         | 2020-04-01T00:00    | 2020-05-31T00:00",
            "0 0 29 2 *         | 2021-03-01T00:00    | 2024-02-29T00:00",
            "0 0 29 2 *         | 2096-03-01T00:00    | 2104-02-29T00:00",
            // names and macros
            "0 0 * JAN,JUL mon  | 2020-02-01T00:00    | 2020-07-06T00:00",
            "15 4 * * sun       | 2020-05-23T00:00    | 2020-05-24T04:15",
            "@daily             | 2020-05-23T10:00    | 2020-05-24T00:00",
            "@hourly            | 2020-05-23T10:00    | 2020-05-23T11:00",
            "@weekly            | 2020-05-23T10:00    | 2020-05-24T00:00",
            "@monthly           | 2020-05-23T10:00    | 2020-06-01T00:00",
            "@yearly            | 2020-05-23T10:00    | 2021-01-01T00:00",
    })
    void computesNextFireTime(String expression, String after, String expected) {
        assertNext(expression, after, expected);
    }

    @Test
    void sundayIsZeroOrSeven() {
        assertNext("0 12 * * 0", "2020-05-23T00:00", "2020-05-24T12:00");
        assertNext("0 12 * * 7", "2020-05-23T00:00", "2020-05-24T12:00");
        assertNext("0 12 * * 5-7", "2020-05-23T13:00", "2020-05-24T12:00");
    }

    @Test
    void dayOfMonthAndDayOfWeekMatchEitherWhenBothRestricted() {
        // The 13th, or any Friday.
        assertNext("0 0 13 * 5", "2020-03-01T00:00", "2020-03-06T00:00");
        assertNext("0 0 13 * 5", "2020-03-07T00:00", "2020-03-13T00:00");
        assertNext("0 0 13 * 5", "2020-03-13T00:00", "2020-03-20T00:00");
    }

    @Test
    void dayOfWeekAloneWhenDayOfMonthIsWildcard() {
        assertNext("0 0 */2 * 5", "2020-03-01T00:00", "2020-03-13T00:00");
    }

    @Test
    void neverFires() {
        ZonedDateTime from = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, UTC);
        assertTrue(CronExpression.parse("0 0 30 2 *").next(from).isEmpty());
    }

    @Test
    void keepsZoneOfInput() {
        ZonedDateTime from = ZonedDateTime.of(2020, 5, 23, 10, 0, 0, 0, NEW_YORK);
        ZonedDateTime next = CronExpression.parse("0 12 * * *").next(from).orElseThrow();
        assertEquals(ZonedDateTime.of(2020, 5, 23, 12, 0, 0, 0, NEW_YORK), next);
    }

    @Test
    void timeSkippedBySpringForwardFiresAfterTheGap() {
        // 2020-03-08 02:00 EST jumps to 03:00 EDT.
        ZonedDateTime from = ZonedDateTime.parse("2020-03-08T01:00-05:00[America/New_York]");
        ZonedDateTime next = CronExpression.parse("30 2 * * *").next(from).orElseThrow();
        assertEquals(ZonedDateTime.parse("2020-03-08T03:30-04:00[America/New_York]"), next);
    }

    @Test
    void timeRepeatedByFallBackFiresOnce() {
        // 2020-11-01 02:00 EDT falls back to 01:00 EST.
        CronExpression cron = CronExpression.parse("30 1 * * *");
        ZonedDateTime first = cron.next(ZonedDateTime.parse("2020-11-01T00:00-04:00[America/New_York]")).orElseThrow();
        assertEquals(ZonedDateTime.parse("2020-11-01T01:30-04:00[America/New_York]"), first);

        ZonedDateTime second = cron.next(first).orElseThrow();
        assertEquals(ZonedDateTime.parse("2020-11-02T01:30-05:00[America/New_York]"), second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "* * * *",
            "* * * * * *",
            "60 * * * *",
            "* 24 * * *",
            "* * 0 * *",
            "* * 32 * *",
            "* * * 13 *",
            "* * * * 8",
            "*/0 * * * *",
            "30-10 * * * *",
            "1,,2 * * * *",
            "a * * * *",
            "* * * FOO *",
            "-5 * * * *",
    })
    void rejectsMalformedExpressions(String expression) {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(expression));
    }

    @Test
    void errorNamesTheExpressionAndField() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 25 * * *"));
        assertEquals("Invalid cron expression '0 25 * * *': hour '25' is outside 0-23", e.getMessage());
    }

    private static void assertNext(String expression, String after, String expected) {
        ZonedDateTime from = LocalDateTime.parse(after).atZone(UTC);
        ZonedDateTime next = CronExpression.parse(expression).next(from).orElseThrow();
        assertEquals(LocalDateTime.parse(expected).atZone(UTC), next);
    }
}
