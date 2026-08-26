package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;

public final class FixedDelaySchedule implements Schedule {
    private final Duration initialDelay;
    private final Duration delay;

    FixedDelaySchedule(Duration initialDelay, Duration delay) {
        this.initialDelay = DelayedSchedule.requireNonNegative(initialDelay, "initialDelay");
        this.delay = DelayedSchedule.requireNonNegative(delay, "delay");
        if (delay.isZero()) throw new IllegalArgumentException("delay must be greater than zero");
    }

    public Duration initialDelay() { return initialDelay; }
    public Duration delay() { return delay; }
}
