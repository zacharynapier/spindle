package io.github.zacharynapier.spindle;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * A standard five-field cron expression: minute, hour, day of month, month and day of week.
 *
 * <p>Fields accept {@code *}, single values, ranges ({@code 1-5}), lists ({@code 1,15,30}) and
 * steps over a range or from a start value ({@code 0-30/5}, {@code 5/15}); a step after {@code *}
 * covers the whole field. Sunday is both 0 and 7 in the day of week field.
 */
public final class CronExpression {

    // Far enough to reach Feb 29 across a skipped leap year such as 2100.
    private static final int MAX_YEARS_AHEAD = 8;

    private enum Field {
        MINUTE("minute", 0, 59),
        HOUR("hour", 0, 23),
        DAY_OF_MONTH("day of month", 1, 31),
        MONTH("month", 1, 12),
        DAY_OF_WEEK("day of week", 0, 7);

        final String label;
        final int min;
        final int max;

        Field(String label, int min, int max) {
            this.label = label;
            this.min = min;
            this.max = max;
        }
    }

    private final String expression;
    private final long minutes;
    private final long hours;
    private final long daysOfMonth;
    private final long months;
    private final long daysOfWeek;

    private CronExpression(String expression, String[] fields) {
        this.expression = expression;
        this.minutes = parseField(fields[0], Field.MINUTE);
        this.hours = parseField(fields[1], Field.HOUR);
        this.daysOfMonth = parseField(fields[2], Field.DAY_OF_MONTH);
        this.months = parseField(fields[3], Field.MONTH);

        long dow = parseField(fields[4], Field.DAY_OF_WEEK);
        if (has(dow, 7)) {
            dow = (dow | 1L) & ~(1L << 7);
        }
        this.daysOfWeek = dow;
    }

    /**
     * Parses a five-field expression.
     *
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static CronExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                    "Expected 5 fields in cron expression '" + expression + "' but found " + fields.length);
        }
        try {
            return new CronExpression(trimmed, fields);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid cron expression '" + expression + "': " + e.getMessage(), e);
        }
    }

    /**
     * Returns the first time strictly after {@code after} that matches this expression, in the
     * same zone.
     *
     * <p>Returns empty if the expression can never match, such as {@code 0 0 30 2 *}.
     */
    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        Objects.requireNonNull(after, "after");
        ZonedDateTime t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        int lastYear = t.getYear() + MAX_YEARS_AHEAD;

        while (t.getYear() <= lastYear) {
            if (!has(months, t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!matchesDay(t.toLocalDate())) {
                t = t.plusDays(1).truncatedTo(ChronoUnit.DAYS);
            } else if (!has(hours, t.getHour())) {
                t = t.plusHours(1).truncatedTo(ChronoUnit.HOURS);
            } else if (!has(minutes, t.getMinute())) {
                t = t.plusMinutes(1);
            } else {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return expression;
    }

    private boolean matchesDay(LocalDate date) {
        return has(daysOfMonth, date.getDayOfMonth())
                && has(daysOfWeek, date.getDayOfWeek().getValue() % 7);
    }

    private static boolean has(long bits, int value) {
        return (bits & (1L << value)) != 0;
    }

    private static long parseField(String field, Field spec) {
        long bits = 0;
        for (String part : field.split(",", -1)) {
            bits |= parsePart(part, spec);
        }
        return bits;
    }

    private static long parsePart(String part, Field spec) {
        String range = part;
        int step = 1;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = parseNumber(part.substring(slash + 1), spec);
            if (step < 1) {
                throw new IllegalArgumentException("step must be positive in " + spec.label + " '" + part + "'");
            }
        }

        int start;
        int end;
        int dash = range.indexOf('-');
        if (range.equals("*")) {
            start = spec.min;
            end = spec.max;
        } else if (dash >= 0) {
            start = parseNumber(range.substring(0, dash), spec);
            end = parseNumber(range.substring(dash + 1), spec);
        } else {
            start = parseNumber(range, spec);
            end = slash >= 0 ? spec.max : start;
        }

        if (start < spec.min || end > spec.max) {
            throw new IllegalArgumentException(
                    spec.label + " '" + part + "' is outside " + spec.min + "-" + spec.max);
        }
        if (start > end) {
            throw new IllegalArgumentException(spec.label + " range '" + part + "' is reversed");
        }

        long bits = 0;
        for (int i = start; i <= end; i += step) {
            bits |= 1L << i;
        }
        return bits;
    }

    private static int parseNumber(String value, Field spec) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not a valid " + spec.label);
        }
    }
}
