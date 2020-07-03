package io.github.zacharynapier.spindle;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A standard five-field cron expression: minute, hour, day of month, month and day of week.
 *
 * <p>Fields accept {@code *}, single values, ranges ({@code 1-5}), lists ({@code 1,15,30}) and
 * steps over a range or from a start value ({@code 0-30/5}, {@code 5/15}); a step after {@code *}
 * covers the whole field. Months and days of week also accept three-letter names, and Sunday is
 * both 0 and 7. As in Vixie cron, when both day of month and
 * day of week are restricted, a day matching either one fires.
 */
public final class CronExpression {

    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "0 0 1 1 *",
            "@annually", "0 0 1 1 *",
            "@monthly", "0 0 1 * *",
            "@weekly", "0 0 * * 0",
            "@daily", "0 0 * * *",
            "@midnight", "0 0 * * *",
            "@hourly", "0 * * * *");

    // Far enough to reach Feb 29 across a skipped leap year such as 2100.
    private static final int MAX_YEARS_AHEAD = 8;

    private enum Field {
        MINUTE("minute", 0, 59),
        HOUR("hour", 0, 23),
        DAY_OF_MONTH("day of month", 1, 31),
        MONTH("month", 1, 12,
                "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"),
        DAY_OF_WEEK("day of week", 0, 7, "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

        final String label;
        final int min;
        final int max;
        final String[] names;

        Field(String label, int min, int max, String... names) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.names = names;
        }
    }

    private final String expression;
    private final long minutes;
    private final long hours;
    private final long daysOfMonth;
    private final long months;
    private final long daysOfWeek;
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;

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

        this.anyDayOfMonth = fields[2].startsWith("*");
        this.anyDayOfWeek = fields[4].startsWith("*");
    }

    /**
     * Parses a five-field expression or one of {@code @yearly}, {@code @monthly},
     * {@code @weekly}, {@code @daily} and {@code @hourly}.
     *
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static CronExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        String expanded = MACROS.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
        String[] fields = expanded.split("\\s+");
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
     * same zone. Matching uses wall-clock time: a time skipped by a daylight saving gap fires at
     * the corresponding instant after the gap, and a time repeated when clocks fall back fires
     * once.
     *
     * <p>Returns empty if the expression can never match, such as {@code 0 0 30 2 *}.
     */
    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        Objects.requireNonNull(after, "after");
        LocalDateTime t = after.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
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
                return Optional.of(ZonedDateTime.ofLocal(t, after.getZone(), after.getOffset()));
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return expression;
    }

    private boolean matchesDay(LocalDate date) {
        boolean dayOfMonth = has(daysOfMonth, date.getDayOfMonth());
        boolean dayOfWeek = has(daysOfWeek, date.getDayOfWeek().getValue() % 7);
        if (anyDayOfMonth || anyDayOfWeek) {
            return dayOfMonth && dayOfWeek;
        }
        return dayOfMonth || dayOfWeek;
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
            start = parseValue(range.substring(0, dash), spec);
            end = parseValue(range.substring(dash + 1), spec);
        } else {
            start = parseValue(range, spec);
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

    private static int parseValue(String value, Field spec) {
        String upper = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < spec.names.length; i++) {
            if (spec.names[i].equals(upper)) {
                return spec.min + i;
            }
        }
        return parseNumber(value, spec);
    }

    private static int parseNumber(String value, Field spec) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not a valid " + spec.label);
        }
    }
}
