package io.github.byzatic.commons.schedulers.unified;

public enum RunState {
    WAITING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    REJECTED,
    SKIPPED
}
