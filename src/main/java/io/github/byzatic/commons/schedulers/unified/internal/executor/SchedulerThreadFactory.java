package io.github.byzatic.commons.schedulers.unified.internal.executor;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

@ApiStatus.Internal
public final class SchedulerThreadFactory implements ThreadFactory {
    private final String prefix;
    private final boolean daemon;
    private final AtomicLong sequence = new AtomicLong();

    public SchedulerThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
        thread.setDaemon(daemon);
        return thread;
    }
}
