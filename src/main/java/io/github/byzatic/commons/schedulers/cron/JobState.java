package io.github.byzatic.commons.schedulers.cron;

/**
 * Task lifecycle statuses.
 */
public enum JobState {SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT}
