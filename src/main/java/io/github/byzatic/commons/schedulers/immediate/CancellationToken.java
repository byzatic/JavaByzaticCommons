package io.github.byzatic.commons.schedulers.immediate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Токен кооперативной отмены. Новый для каждого запуска.
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
     * Удобно для долгих циклов: выбросить InterruptedException при стопе.
     */
    public void throwIfStopRequested() throws InterruptedException {
        if (isStopRequested()) throw new InterruptedException("Stop requested: " + reason);
    }
}
