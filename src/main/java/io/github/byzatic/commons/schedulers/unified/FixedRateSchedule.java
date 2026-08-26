package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;

public final class FixedRateSchedule implements Schedule {
    private final Duration initialDelay;
    private final Duration period;

    FixedRateSchedule(Duration initialDelay, Duration period) {
        this.initialDelay = DelayedSchedule.requireNonNegative(initialDelay, "initialDelay");
        this.period = DelayedSchedule.requireNonNegative(period, "period");
        if (period.isZero()) throw new IllegalArgumentException("period must be greater than zero");
    }

    public Duration initialDelay() { return initialDelay; }
    public Duration period() { return period; }
}
