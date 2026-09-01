package io.github.byzatic.commons.schedulers.unified.internal.execution;

import io.github.byzatic.commons.schedulers.unified.ExecutionLane;
import io.github.byzatic.commons.schedulers.unified.RunHandle;
import io.github.byzatic.commons.schedulers.unified.UnifiedSchedulerInterface;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Thread-safe FIFO execution lane that borrows its worker from a {@link UnifiedSchedulerInterface}. */
@ApiStatus.Internal
public final class SerialExecutionLane implements ExecutionLane {
    private final UnifiedSchedulerInterface scheduler;
    private final String name;
    private final Object lock = new Object();
    private final Queue<LaneTask> tasks = new ArrayDeque<>();
    private boolean accepting = true;
    private boolean draining;
    /** Guarded by {@link #lock}; distinguishes graceful drain from forced shutdown. */
    private boolean forceStopping;
    private boolean terminated;
    private RunHandle drainHandle;

    public SerialExecutionLane(UnifiedSchedulerInterface scheduler, String name) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.name = requireName(name);
    }

    @Override
    public CompletionStage<Void> submit(Runnable task) {
        LaneTask laneTask = new LaneTask(Objects.requireNonNull(task, "task"));
        boolean startDrain = false;
        synchronized (lock) {
            if (!accepting) {
                throw new RejectedExecutionException("Execution lane '" + name + "' is shut down");
            }
            tasks.add(laneTask);
            if (!draining) {
                draining = true;
                startDrain = true;
            }
        }
        if (startDrain) {
            dispatchDrain(true);
        }
        return laneTask.completion;
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            accepting = false;
            markTerminatedIfIdle();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> abandoned = new ArrayList<>();
        RunHandle currentDrain;
        synchronized (lock) {
            accepting = false;
            forceStopping = true;
            LaneTask task;
            while ((task = tasks.poll()) != null) {
                abandoned.add(task.command);
                task.completion.completeExceptionally(
                        new RejectedExecutionException("Execution lane '" + name + "' was stopped"));
            }
            currentDrain = drainHandle;
            markTerminatedIfIdle();
        }
        if (currentDrain != null) {
            try {
                currentDrain.cancel("Execution lane '" + name + "' was stopped", Duration.ZERO);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return Collections.unmodifiableList(abandoned);
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (lock) {
            while (!terminated) {
                if (remaining <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                remaining = deadline - System.nanoTime();
            }
            return true;
        }
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return !accepting;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void dispatchDrain(boolean propagateRejection) {
        try {
            RunHandle submitted = scheduler.submit(this::drain);
            boolean cancelSubmitted = false;
            synchronized (lock) {
                if (draining) {
                    drainHandle = submitted;
                    cancelSubmitted = forceStopping;
                }
            }
            if (cancelSubmitted) {
                try {
                    submitted.cancel(
                            "Execution lane '" + name + "' was stopped",
                            Duration.ZERO
                    );
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (RejectedExecutionException rejection) {
            rejectQueued(rejection);
            if (propagateRejection) {
                throw rejection;
            }
        }
    }

    private void drain() {
        while (true) {
            LaneTask task;
            synchronized (lock) {
                task = tasks.poll();
                if (task == null) {
                    draining = false;
                    drainHandle = null;
                    markTerminatedIfIdle();
                    return;
                }
            }
            task.run();
        }
    }

    private void rejectQueued(RejectedExecutionException rejection) {
        synchronized (lock) {
            LaneTask task;
            while ((task = tasks.poll()) != null) {
                task.completion.completeExceptionally(rejection);
            }
            draining = false;
            markTerminatedIfIdle();
        }
    }

    private void markTerminatedIfIdle() {
        if (!accepting && !draining && tasks.isEmpty() && !terminated) {
            terminated = true;
            lock.notifyAll();
        }
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return value;
    }

    private static final class LaneTask {
        private final Runnable command;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private LaneTask(Runnable command) {
            this.command = command;
        }

        private void run() {
            try {
                command.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }
    }
}
