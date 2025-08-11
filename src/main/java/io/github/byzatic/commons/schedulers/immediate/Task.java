package io.github.byzatic.commons.schedulers.immediate;

/**
 * Task interface. Always check the token periodically!
 */
public interface Task {
    void run(CancellationToken token) throws Exception;

    /**
     * Called immediately when a soft stop is requested (optional).
     */
    default void onStopRequested() {
    }
}
