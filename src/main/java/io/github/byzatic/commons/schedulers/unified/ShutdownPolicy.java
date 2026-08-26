package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;

public final class ShutdownPolicy {
    private final Duration gracefulTimeout;
    private final Duration forcedTimeout;

    private ShutdownPolicy(Builder builder) {
        gracefulTimeout = builder.gracefulTimeout;
        forcedTimeout = builder.forcedTimeout;
    }

    public static Builder builder() { return new Builder(); }
    public static ShutdownPolicy defaults() { return builder().build(); }
    public Duration gracefulTimeout() { return gracefulTimeout; }
    public Duration forcedTimeout() { return forcedTimeout; }

    public static final class Builder {
        private Duration gracefulTimeout = Duration.ofSeconds(10);
        private Duration forcedTimeout = Duration.ofSeconds(5);
        private Builder() { }
        public Builder gracefulTimeout(Duration value) { gracefulTimeout = DelayedSchedule.requireNonNegative(value, "gracefulTimeout"); return this; }
        public Builder forcedTimeout(Duration value) { forcedTimeout = DelayedSchedule.requireNonNegative(value, "forcedTimeout"); return this; }
        public ShutdownPolicy build() { return new ShutdownPolicy(this); }
    }
}
