package io.github.byzatic.commons.schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Токен кооперативной отмены. Создаётся новый на каждый запуск.
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
     * Хелпер: бросает InterruptedException если пришёл стоп.
     */
    public void throwIfStopRequested() throws InterruptedException {
        if (isStopRequested()) throw new InterruptedException("Stop requested: " + reason);
    }
}
