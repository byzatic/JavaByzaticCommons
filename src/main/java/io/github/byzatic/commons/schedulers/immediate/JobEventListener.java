package io.github.byzatic.commons.schedulers.immediate;

import java.util.UUID;

/**
 * Слушатель событий по задачам.
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
