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
        CronExpr expr = CronExpr.parse("0 0 12 12 8 *"); // убрали DOW
        Instant now = Instant.parse("2025-08-08T00:00:00Z");
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        assertEquals(Instant.parse("2025-08-12T12:00:00Z"), next.get());
    }

    @Test
    void respectsMonthDayHourMinuteSecondFilters_AND_semantics() {
        // 12 августа в 12:00:00, и при этом DOW=5 (пятница)
        CronExpr expr = CronExpr.parse("0 0 12 12 8 5");

        // 2025-08-08 — это пятница, но не 12-е => первый матч будет тогда,
        // когда 12-е августа выпадет на пятницу (в будущем).
        Instant now = Instant.parse("2025-08-08T00:00:00Z");
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));

        assertTrue(next.isPresent());
        // Точную дату предсказывать не будем — просто проверим поля:
        Instant t = next.get();
        ZonedDateTime z = ZonedDateTime.ofInstant(t, ZoneId.of("UTC"));
        assertEquals(12, z.getDayOfMonth());
        assertEquals(8, z.getMonthValue());
        assertEquals(12, z.getHour());
        assertEquals(0, z.getMinute());
        assertEquals(0, z.getSecond());
        assertEquals(5 % 7, z.getDayOfWeek().getValue() % 7); // пятница
    }

    @Test
    void respectsDayOfWeekFilter() {
        CronExpr expr = CronExpr.parse("0 30 9 * * 5"); // каждую пятницу в 09:30:00
        Instant now = Instant.parse("2025-08-08T00:00:00Z"); // это пятница
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        assertEquals(Instant.parse("2025-08-08T09:30:00Z"), next.get());
    }

    @Test
    void dayOfWeekOnly_whenDomIsStar() {
        CronExpr expr = CronExpr.parse("0 30 9 * * 5"); // каждую пятницу в 09:30:00
        Instant now = Instant.parse("2025-08-08T00:00:00Z"); // пятница
        Optional<Instant> next = expr.next(now, ZoneId.of("UTC"));
        assertTrue(next.isPresent());
        assertEquals(Instant.parse("2025-08-08T09:30:00Z"), next.get());
    }
}