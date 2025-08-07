package io.github.byzatic.commons.schedulers;

import org.jetbrains.annotations.NotNull;

public interface SchedulerInterface {
    @NotNull String addJob(@NotNull Task task);

    void removeJob(@NotNull String taskId);

    void observer(@NotNull SchedulerObserverInterface schedulerObserver);

    void cleanup();
}
