package io.github.byzatic.commons.schedulers.cron;

/**
 * Статусы жизненного цикла задачи.
 */
public enum JobState {SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT}
