package io.github.byzatic.commons.schedulers.cron;

import java.util.UUID;

/**
 * Job event listener.
 */
public interface JobEventListener {
    default void onStart(UUID jobId) {
    }

    default void onComplete(UUID jobId) {
    }

    default void onError(UUID jobId, Throwable error) {
    }

    default void onTimeout(UUID jobId) {
    }

    default void onCancelled(UUID jobId) {
    }
}
