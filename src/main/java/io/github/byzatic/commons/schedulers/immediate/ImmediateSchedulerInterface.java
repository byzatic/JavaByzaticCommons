package io.github.byzatic.commons.schedulers.immediate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImmediateSchedulerInterface extends AutoCloseable {
    void addListener(JobEventListener l);

    void removeListener(JobEventListener l);

    UUID addTask(Task task);

    void stopTask(UUID jobId, Duration grace);

    boolean removeTask(UUID jobId);

    boolean removeTask(UUID jobId, Duration grace);

    Optional<JobInfo> query(UUID jobId);

    List<JobInfo> listTasks();
}
