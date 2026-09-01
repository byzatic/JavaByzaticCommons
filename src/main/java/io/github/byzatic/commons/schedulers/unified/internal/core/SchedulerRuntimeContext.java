package io.github.byzatic.commons.schedulers.unified.internal.core;

import io.github.byzatic.commons.schedulers.unified.RunOutcome;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SchedulerRun;
import io.github.byzatic.commons.schedulers.unified.internal.scheduling.SchedulerSchedule;
import io.github.byzatic.commons.schedulers.unified.internal.timing.SchedulerTrigger;
import org.jetbrains.annotations.ApiStatus;

import java.time.Instant;
import java.util.UUID;

/** Internal port used by isolated scheduler state machines. */
@ApiStatus.Internal
public interface SchedulerRuntimeContext {
    Instant now();

    boolean isAccepting();

    boolean offerScheduleTrigger(SchedulerSchedule schedule, long generation, Instant instant);

    void removeTrigger(SchedulerTrigger trigger);

    void removeTriggers(SchedulerSchedule schedule);

    void removeQueuedRun(SchedulerRun run);

    void removeSchedule(UUID id, SchedulerSchedule schedule);

    void onRunExited(SchedulerRun run);

    void submitRun(SchedulerRun run, boolean propagateRejection);

    void fireStart(UUID scheduleId, UUID runId);

    void fireOutcome(UUID scheduleId, RunOutcome outcome);

    void fireRejected(UUID scheduleId, UUID runId, Throwable failure);

    void fireSkipped(UUID scheduleId);
}
