package io.github.byzatic.commons.schedulers.unified;

import java.util.UUID;

public interface ScheduleEventListener {
    default void onRunStart(UUID scheduleId, UUID runId) { }
    default void onRunComplete(UUID scheduleId, RunOutcome outcome) { }
    default void onRunRejected(UUID scheduleId, UUID runId, Throwable failure) { }
    default void onRunSkipped(UUID scheduleId) { }
}
