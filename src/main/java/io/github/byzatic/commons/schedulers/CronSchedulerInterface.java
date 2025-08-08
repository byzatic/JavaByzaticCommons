package io.github.byzatic.commons.schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CronSchedulerInterface extends AutoCloseable {
    void addListener(JobEventListener l);

    void removeListener(JobEventListener l);

    UUID addJob(String cron, CronTask task);

    UUID addJob(String cron, CronTask task, boolean disallowOverlap);

    boolean removeJob(UUID jobId);

    boolean removeJob(UUID jobId, Duration grace);

    void stopJob(UUID jobId, Duration grace);

    Optional<JobInfo> query(UUID jobId);

    List<JobInfo> listJobs();
}
