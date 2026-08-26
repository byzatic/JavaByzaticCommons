package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.util.Objects;

public final class DelayedSchedule implements Schedule {
    private final Duration delay;

    DelayedSchedule(Duration delay) {
        this.delay = requireNonNegative(delay, "delay");
    }

    public Duration delay() { return delay; }

    static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
