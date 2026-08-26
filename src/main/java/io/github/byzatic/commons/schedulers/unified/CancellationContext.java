package io.github.byzatic.commons.schedulers.unified;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooperative cancellation signal supplied to every task run. */
public final class CancellationContext {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private volatile String reason = "";

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    public String reason() {
        return reason;
    }

    public void throwIfCancellationRequested() throws InterruptedException {
        if (isCancellationRequested()) {
            throw new InterruptedException("Cancellation requested: " + reason);
        }
    }

    boolean requestCancellation(String cancellationReason) {
        Objects.requireNonNull(cancellationReason, "cancellationReason");
        reason = cancellationReason;
        return cancellationRequested.compareAndSet(false, true);
    }
}
