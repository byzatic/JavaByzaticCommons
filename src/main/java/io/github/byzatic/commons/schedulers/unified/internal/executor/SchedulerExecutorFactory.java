package io.github.byzatic.commons.schedulers.unified.internal.executor;

import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class SchedulerExecutorFactory {
    private SchedulerExecutorFactory() {
        throw new AssertionError("No instances");
    }

    public static ThreadPoolExecutor create(
            int parallelism,
            int queueCapacity,
            Duration keepAlive,
            boolean allowCoreThreadTimeout,
            ThreadFactory threadFactory
    ) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                keepAlive.toMillis(),
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(allowCoreThreadTimeout);
        return executor;
    }

    public static ThreadPoolExecutor validate(ThreadPoolExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        if (executor.isShutdown()) {
            throw new IllegalArgumentException("executor is already shut down");
        }
        if (!(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy)) {
            throw new IllegalArgumentException("executor must use ThreadPoolExecutor.AbortPolicy");
        }
        return executor;
    }
}
