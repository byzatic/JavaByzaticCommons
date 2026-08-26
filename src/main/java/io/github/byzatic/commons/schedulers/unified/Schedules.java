package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.time.ZoneId;

/** Static factories for immutable schedule values. */
public final class Schedules {
    private static final ImmediateSchedule IMMEDIATE = new ImmediateSchedule();
    private Schedules() { }

    public static ImmediateSchedule immediate() { return IMMEDIATE; }
    public static DelayedSchedule after(Duration delay) { return new DelayedSchedule(delay); }
    public static FixedDelaySchedule fixedDelay(Duration initialDelay, Duration delay) {
        return new FixedDelaySchedule(initialDelay, delay);
    }
    public static FixedRateSchedule fixedRate(Duration initialDelay, Duration period) {
        return new FixedRateSchedule(initialDelay, period);
    }
    public static CronSchedule cron(String expression) {
        return cron(expression, ZoneId.systemDefault(), false);
    }
    public static CronSchedule cron(String expression, ZoneId zone) {
        return cron(expression, zone, false);
    }
    public static CronSchedule cron(String expression, ZoneId zone, boolean runImmediately) {
        return new CronSchedule(CronExpression.parse(expression), zone, runImmediately);
    }
}
