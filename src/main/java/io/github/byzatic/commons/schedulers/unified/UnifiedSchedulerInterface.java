package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnifiedSchedulerInterface extends AutoCloseable {
    RunHandle submit(ScheduledTask task);
    RunHandle submit(Runnable task);
    RunHandle schedule(ScheduledTask task, Duration delay);
    ScheduleHandle schedule(ScheduledTask task, Schedule schedule);
    ScheduleHandle schedule(ScheduledTask task, Schedule schedule, ScheduleOptions options);
    Optional<ScheduleHandle> findSchedule(UUID scheduleId);
    List<ScheduleHandle> listSchedules();
    void addListener(ScheduleEventListener listener);
    void removeListener(ScheduleEventListener listener);
    void shutdown();
    List<RunHandle> shutdownNow();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    boolean isShutdown();
    boolean isTerminated();
    @Override void close();
}
