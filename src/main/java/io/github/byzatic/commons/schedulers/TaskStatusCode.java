package io.github.byzatic.commons.schedulers;

public enum TaskStatusCode {
    RUNNING,
    COMPLETE,
    FAULT,
    UNINITIALIZED;

    public static String getStatusCodeMessage(TaskStatusCode code) {
        return switch (code) {
            case RUNNING -> "RUNNING: Task is running.";
            case COMPLETE -> "COMPLETE: Task has completed successfully.";
            case FAULT -> "FAULT: Task execution failed.";
            case UNINITIALIZED -> "UNINITIALIZED: Task is uninitialized.";
        };
    }
}