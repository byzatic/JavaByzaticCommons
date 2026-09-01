package io.github.byzatic.commons.schedulers.cron;

import io.github.byzatic.commons.schedulers.unified.CancellationContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token. Created anew for each run.
 */
public final class CancellationToken {
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final CancellationContext delegate;
    private volatile String reason = "";

    CancellationToken() {
        this.delegate = null;
    }

    private CancellationToken(CancellationContext delegate) {
        this.delegate = delegate;
    }

    static CancellationToken adapt(CancellationContext delegate) {
        return new CancellationToken(delegate);
    }

    public boolean isStopRequested() {
        return delegate == null ? stop.get() : delegate.isCancellationRequested();
    }

    public String reason() {
        return delegate == null ? reason : delegate.reason();
    }

    void requestStop(String reason) {
        if (delegate != null) {
            throw new UnsupportedOperationException("Adapted token is controlled by UnifiedScheduler");
        }
        this.reason = reason;
        stop.set(true);
    }

    /**
     * Helper: throws InterruptedException if a stop has been requested.
     */
    public void throwIfStopRequested() throws InterruptedException {
        if (isStopRequested()) throw new InterruptedException("Stop requested: " + reason());
    }
}
