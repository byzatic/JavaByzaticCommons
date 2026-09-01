package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleHandle {
    UUID id();
    Schedule schedule();
    ScheduleState state();
    Optional<Instant> nextExecutionTime();
    Optional<RunOutcome> lastOutcome();
    List<RunHandle> activeRuns();
    boolean pause();
    boolean resume();
    boolean cancel();
    boolean cancel(Duration grace) throws InterruptedException;
    boolean stopActiveRuns(Duration grace) throws InterruptedException;
}
