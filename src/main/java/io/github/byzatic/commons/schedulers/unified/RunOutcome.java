package io.github.byzatic.commons.schedulers.unified;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable terminal result of one task execution. */
public final class RunOutcome {
    private final UUID runId;
    private final RunState state;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Throwable failure;

    public RunOutcome(UUID runId, RunState state, Instant startedAt,
                      Instant completedAt, Throwable failure) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.state = Objects.requireNonNull(state, "state");
        this.startedAt = startedAt;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.failure = failure;
    }

    public UUID runId() { return runId; }
    public RunState state() { return state; }
    public Optional<Instant> startedAt() { return Optional.ofNullable(startedAt); }
    public Instant completedAt() { return completedAt; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
}
