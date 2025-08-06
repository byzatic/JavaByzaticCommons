package io.github.byzatic.commons.schedulers;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface SchedulerInterface {
    void addJob(@NotNull JobDetailInterface jobDetailInterface);

    void runAllJobs(@NotNull Boolean isJoinThreads);

    void removeAllJobs(@NotNull Long defaultForcedTerminationIntervalMinutes);

    void removeJob(@NotNull JobDetailInterface jobDetailInterface, @NotNull Long defaultForcedTerminationIntervalMinutes);

    void cleanup();

    @NotNull
    Boolean isJobActive(@NotNull JobDetailInterface jobDetailInterface);

    @NotNull
    List<JobDetailInterface> listJobDetails();
}
