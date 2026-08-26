package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.util.Objects;

/** Immutable per-schedule execution policies. */
public final class ScheduleOptions {
    private final OverlapPolicy overlapPolicy;
    private final MisfirePolicy misfirePolicy;
    private final FailurePolicy failurePolicy;
    private final Duration cancellationGrace;

    private ScheduleOptions(Builder builder) {
        overlapPolicy = builder.overlapPolicy;
        misfirePolicy = builder.misfirePolicy;
        failurePolicy = builder.failurePolicy;
        cancellationGrace = builder.cancellationGrace;
    }

    public static Builder builder() { return new Builder(); }
    public static ScheduleOptions defaults() { return builder().build(); }
    public OverlapPolicy overlapPolicy() { return overlapPolicy; }
    public MisfirePolicy misfirePolicy() { return misfirePolicy; }
    public FailurePolicy failurePolicy() { return failurePolicy; }
    public Duration cancellationGrace() { return cancellationGrace; }

    public static final class Builder {
        private OverlapPolicy overlapPolicy = OverlapPolicy.ALLOW;
        private MisfirePolicy misfirePolicy = MisfirePolicy.SKIP;
        private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;
        private Duration cancellationGrace = Duration.ofSeconds(10);
        private Builder() { }
        public Builder overlapPolicy(OverlapPolicy value) { overlapPolicy = Objects.requireNonNull(value); return this; }
        public Builder misfirePolicy(MisfirePolicy value) { misfirePolicy = Objects.requireNonNull(value); return this; }
        public Builder failurePolicy(FailurePolicy value) { failurePolicy = Objects.requireNonNull(value); return this; }
        public Builder cancellationGrace(Duration value) {
            cancellationGrace = DelayedSchedule.requireNonNegative(value, "cancellationGrace"); return this;
        }
        public ScheduleOptions build() { return new ScheduleOptions(this); }
    }
}
