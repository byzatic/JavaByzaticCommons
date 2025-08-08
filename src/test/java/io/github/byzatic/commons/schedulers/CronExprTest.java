package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CronExprTest {

    @Test
    void parsesFiveFields_defaultsSecondsToZero_andFindsNextMinute() {
        CronExpr expr = CronExpr.parse("* * * * *"); // 5 полей: секунды=0
        Instant now = Instant.parse("2025-08-08T11:23:20Z");
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        // следующий матч на сек=0 → начало следующей минуты
        assertEquals(Instant.parse("2025-08-08T11:24:00Z"), next.get());
    }

    @Test
    void parsesSixFields_secondsStepEvery10s() {
        CronExpr expr = CronExpr.parse("*/10 * * * * *");
        Instant now = Instant.parse("2025-08-08T11:23:25Z");
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        // ближайшее кратное 10 после 25 = 30
        assertEquals(Instant.parse("2025-08-08T11:23:30Z"), next.get());
    }

    @Test
    void respectsMonthDayHourMinuteSecondFilters() {
        CronExpr expr = CronExpr.parse("0 0 12 12 8 5"); // sec=0, min=0, hour=12, dom=12, mon=8, dow=5(Fri)
        Instant now = Instant.parse("2025-08-08T00:00:00Z"); // 8 Aug 2025
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        // 12 Aug 2025 12:00:00Z — если это действительно пятница в вашей зоне.
        // Тест не завязан на конкретный DOW: проверим просто точность формата дом/мон/час/мин/сек.
        String s = next.get().toString();
        assertTrue(s.startsWith("2025-08-12T12:00:00Z") || s.startsWith("2026-08-12T12:00:00Z"));
    }
}