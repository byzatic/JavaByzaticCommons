package io.github.byzatic.commons.schedulers.unified;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Objects;
import java.util.Optional;

/** Immutable five- or six-field cron expression with seconds precision. */
public final class CronExpression {
    private final String source;
    private final BitSet seconds = new BitSet(60);
    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);
    private final BitSet months = new BitSet(13);
    private final BitSet daysOfWeek = new BitSet(7);

    private CronExpression(String source) { this.source = source; }

    public static CronExpression parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException("Cron must have 5 or 6 fields: " + expression);
        }
        CronExpression result = new CronExpression(expression.trim());
        int index = 0;
        if (fields.length == 6) result.parseField(fields[index++], 0, 59, result.seconds);
        else result.seconds.set(0);
        result.parseField(fields[index++], 0, 59, result.minutes);
        result.parseField(fields[index++], 0, 23, result.hours);
        result.parseField(fields[index++], 1, 31, result.daysOfMonth);
        result.parseField(fields[index++], 1, 12, result.months);
        result.parseField(fields[index], 0, 6, result.daysOfWeek);
        return result;
    }

    private void parseField(String field, int min, int max, BitSet target) {
        for (String part : field.split(",")) {
            String rangePart = part;
            int step = 1;
            if (part.contains("/")) {
                String[] split = part.split("/", -1);
                if (split.length != 2) throw new IllegalArgumentException("Invalid cron field: " + part);
                rangePart = split[0];
                step = Integer.parseInt(split[1]);
                if (step <= 0) throw new IllegalArgumentException("Cron step must be positive: " + part);
            }
            int start;
            int end;
            if ("*".equals(rangePart)) {
                start = min; end = max;
            } else if (rangePart.contains("-")) {
                String[] range = rangePart.split("-", -1);
                if (range.length != 2) throw new IllegalArgumentException("Invalid cron range: " + part);
                start = Integer.parseInt(range[0]); end = Integer.parseInt(range[1]);
            } else {
                start = end = Integer.parseInt(rangePart);
            }
            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException("Cron value out of range: " + part);
            }
            for (int value = start; value <= end; value += step) target.set(value);
        }
        if (target.isEmpty()) throw new IllegalArgumentException("Cron field is empty: " + field);
    }

    public Optional<Instant> next(Instant from, ZoneId zone) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(zone, "zone");
        ZonedDateTime value = ZonedDateTime.ofInstant(from, zone).plusSeconds(1).withNano(0);
        for (int i = 0; i < 366 * 24 * 60 * 60 * 2; i++) {
            if (!months.get(value.getMonthValue())) { value = value.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0); continue; }
            if (!daysOfMonth.get(value.getDayOfMonth())) { value = value.plusDays(1).withHour(0).withMinute(0).withSecond(0); continue; }
            if (!daysOfWeek.get(value.getDayOfWeek().getValue() % 7)) { value = value.plusDays(1).withHour(0).withMinute(0).withSecond(0); continue; }
            if (!hours.get(value.getHour())) { value = value.plusHours(1).withMinute(0).withSecond(0); continue; }
            if (!minutes.get(value.getMinute())) { value = value.plusMinutes(1).withSecond(0); continue; }
            if (!seconds.get(value.getSecond())) { value = value.plusSeconds(1); continue; }
            return Optional.of(value.toInstant());
        }
        return Optional.empty();
    }

    @Override public String toString() { return source; }
}
