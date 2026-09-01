package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * A logical, strictly sequential execution lane backed by a shared scheduler.
 *
 * <p>The lane does not own a thread or the scheduler. Tasks execute in FIFO order and never
 * overlap, even when the backing scheduler uses multiple worker threads.</p>
 */
public interface ExecutionLane extends AutoCloseable {
    /** Submits a task and returns a stage that reports its completion or failure. */
    CompletionStage<Void> submit(Runnable task);

    /** Stops accepting new tasks and drains already accepted tasks in FIFO order. */
    void shutdown();

    /** Rejects queued tasks and interrupts the currently draining worker when possible. */
    List<Runnable> shutdownNow();

    /** Waits until shutdown has been requested and all accepted work has terminated. */
    boolean awaitTermination(Duration timeout) throws InterruptedException;

    /** Returns whether the lane no longer accepts tasks. */
    boolean isShutdown();

    /** Returns whether the lane has shut down and has no active or queued task. */
    boolean isTerminated();

    /** Performs a graceful lane shutdown without closing the backing scheduler. */
    @Override void close();
}
