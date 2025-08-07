package io.github.byzatic.commons.schedulers;

import java.util.Map;

public interface SchedulerObserverInterface {
    void onTasksError(Map<String, String> tasksErrorMap);
}
