package io.github.byzatic.commons.schedulers.cron;

/**
 * Task interface. Always check the token!
 */
public interface CronTask {
    void run(CancellationToken token) throws Exception;

    /**
     * Called when a soft stop is requested (optional).
     */
    default void onStopRequested() {
    }
}
