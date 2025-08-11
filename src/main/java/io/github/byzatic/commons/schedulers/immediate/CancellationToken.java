package io.github.byzatic.commons.schedulers.immediate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token. Created anew for each run.
 */
public final class CancellationToken {
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private volatile String reason = "";

    public boolean isStopRequested() {
        return stop.get();
    }

    public String reason() {
        return reason;
    }

    void requestStop(String reason) {
        this.reason = reason;
        stop.set(true);
    }

    /**
     * Helper: throws InterruptedException if a stop has been requested.
     */
    public void throwIfStopRequested() throws InterruptedException {
        if (isStopRequested()) throw new InterruptedException("Stop requested: " + reason);
    }
}
