package io.github.byzatic.commons.schedulers.unified;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooperative cancellation signal supplied to every task run. */
public final class CancellationContext {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private volatile String reason = "";

    /**
     * Returns whether cancellation has been requested for this run.
     *
     * @return {@code true} after the scheduler requests cancellation
     */
    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /**
     * Returns the cancellation reason, or an empty string before cancellation is requested.
     *
     * @return cancellation reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Fails cooperatively when cancellation has been requested.
     *
     * @throws InterruptedException when cancellation has been requested
     */
    public void throwIfCancellationRequested() throws InterruptedException {
        if (isCancellationRequested()) {
            throw new InterruptedException("Cancellation requested: " + reason);
        }
    }

    /**
     * Performs the scheduler-internal cancellation transition. Tasks should only observe the
     * context through the other methods.
     *
     * @param cancellationReason non-null reason exposed to the task
     * @return {@code true} when this call performed the first cancellation transition
     */
    @ApiStatus.Internal
    public boolean requestCancellation(String cancellationReason) {
        Objects.requireNonNull(cancellationReason, "cancellationReason");
        reason = cancellationReason;
        return cancellationRequested.compareAndSet(false, true);
    }
}
