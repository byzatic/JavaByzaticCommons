package io.github.byzatic.commons.schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Optional;

// ======== CronExpr с поддержкой секунд (6 полей) ========
final class CronExpr {
    // Если задано 6 полей: sec min hour dom mon dow
    // Если задано 5 полей:      min hour dom mon dow  (sec=0)
    private final BitSet seconds = new BitSet(60);
    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet dom = new BitSet(32);  // 1..31
    private final BitSet months = new BitSet(13); // 1..12
    private final BitSet dow = new BitSet(7);  // 0..6 (0=Sunday)

    static CronExpr parse(String s) {
        String[] p = s.trim().split("\\s+");
        if (p.length != 5 && p.length != 6)
            throw new IllegalArgumentException("Cron must have 5 or 6 fields (with seconds): " + s);

        CronExpr ce = new CronExpr();
        int idx = 0;
        if (p.length == 6) {
            ce.parseField(p[idx++], 0, 59, ce.seconds);  // sec
        } else {
            // 5 полей — секунды фиксируем в 0
            ce.seconds.set(0);
        }
        ce.parseField(p[idx++], 0, 59, ce.minutes);      // min
        ce.parseField(p[idx++], 0, 23, ce.hours);        // hour
        ce.parseField(p[idx++], 1, 31, ce.dom);          // day of month
        ce.parseField(p[idx++], 1, 12, ce.months);       // month
        ce.parseField(p[idx], 0, 6, ce.dow);           // day of week (0=Sun)

        if (ce.seconds.isEmpty() || ce.minutes.isEmpty() || ce.hours.isEmpty()
                || ce.dom.isEmpty() || ce.months.isEmpty() || ce.dow.isEmpty()) {
            throw new IllegalArgumentException("Cron field parsed to empty set: " + s);
        }
        return ce;
    }

    private void parseField(String f, int min, int max, BitSet out) {
        if (f.equals("*")) {
            out.set(min, max + 1);
            return;
        }
        for (String part : f.split(",")) {
            String stepPart = part;
            int step = 1;
            if (part.contains("/")) {
                String[] ar = part.split("/");
                stepPart = ar[0];
                step = Integer.parseInt(ar[1]);
            }
            int start, end;
            if (stepPart.equals("*")) {
                start = min;
                end = max;
            } else if (stepPart.contains("-")) {
                String[] r = stepPart.split("-");
                start = Integer.parseInt(r[0]);
                end = Integer.parseInt(r[1]);
            } else {
                start = end = Integer.parseInt(stepPart);
            }
            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException("Out of range: " + part);
            }
            for (int v = start; v <= end; v += step) out.set(v);
        }
    }

    Optional<Instant> next(Instant from, ZoneId zone) {
        ZonedDateTime z = ZonedDateTime.ofInstant(from, zone)
                .plusSeconds(1)
                .withNano(0);

        // Простой перебор по секундам вперёд (до 2 лет)
        for (int i = 0; i < 366 * 24 * 60 * 60 * 2; i++) {
            if (!months.get(z.getMonthValue())) {
                z = z.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!dom.get(z.getDayOfMonth())) {
                z = z.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!dow.get(z.getDayOfWeek().getValue() % 7)) {
                z = z.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!hours.get(z.getHour())) {
                z = z.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.get(z.getMinute())) {
                z = z.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds.get(z.getSecond())) {
                z = z.plusSeconds(1);
                continue;
            }
            return Optional.of(z.toInstant());
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "CronExpr{...}";
    }
}
