package io.github.byzatic.commons.schedulers.unified;

/** A unit of work managed by {@link UnifiedScheduler}. */
@FunctionalInterface
public interface ScheduledTask {
    void run(CancellationContext cancellationContext) throws Exception;

    default void onCancellationRequested() {
    }
}
