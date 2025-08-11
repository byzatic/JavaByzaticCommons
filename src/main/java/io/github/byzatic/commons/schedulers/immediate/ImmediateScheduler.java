package io.github.byzatic.commons.schedulers.immediate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ImmediateScheduler — a simple scheduler without cron:
 * - Add a task — it starts immediately.
 * - Supports soft-cancel via CancellationToken and termination on timeout.
 * - Events: start/complete/error/timeout/cancelled.
 * - Configurable ThreadPoolExecutor via Builder.
 */
public final class ImmediateScheduler implements ImmediateSchedulerInterface {
    private final ThreadPoolExecutor executor;
    private final long defaultGraceMillis;
    private final List<JobEventListener> listeners;

    private final Map<UUID, JobRecord> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private ImmediateScheduler(ThreadPoolExecutor executor, long defaultGraceMillis, List<JobEventListener> listeners) {
        this.executor = executor;
        this.defaultGraceMillis = defaultGraceMillis;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
    }

    public static final class Builder {
        private ThreadPoolExecutor executor;
        private long defaultGraceMillis = 10_000; // 10s
        private final List<JobEventListener> listeners = new CopyOnWriteArrayList<>();

        /**
         * Provide your own custom thread pool.
         */
        public Builder executor(ThreadPoolExecutor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Default grace period for soft stop.
         */
        public Builder defaultGrace(Duration grace) {
            this.defaultGraceMillis = Objects.requireNonNull(grace).toMillis();
            return this;
        }

        public Builder addListener(JobEventListener l) {
            listeners.add(Objects.requireNonNull(l));
            return this;
        }

        public ImmediateScheduler build() {
            if (executor == null) {
                executor = new ThreadPoolExecutor(
                        Math.max(2, Runtime.getRuntime().availableProcessors()),
                        Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                        60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "immediate-exec-" + UUID.randomUUID());
                            t.setDaemon(false);
                            t.setUncaughtExceptionHandler((th, ex) ->
                                    System.err.println("[ImmediateScheduler] Uncaught in " + th.getName() + ": " + ex));
                            return t;
                        },
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );
                executor.allowCoreThreadTimeOut(true);
            }
            return new ImmediateScheduler(executor, defaultGraceMillis, listeners);
        }
    }

    // ======== Public API ========

    /**
     * Register an event listener.
     */
    @Override
    public void addListener(JobEventListener l) {
        listeners.add(Objects.requireNonNull(l));
    }

    /**
     * Remove an event listener.
     */
    @Override
    public void removeListener(JobEventListener l) {
        listeners.remove(l);
    }

    /**
     * Add a task: it starts immediately. Returns the UUID.
     */
    @Override
    public UUID addTask(Task task) {
        Objects.requireNonNull(task);
        UUID id = UUID.randomUUID();
        JobRecord rec = new JobRecord(id, task);
        jobs.put(id, rec);
        submitRun(rec);
        return id;
    }

    /**
     * Soft stop command for the current run, followed by termination on timeout.
     */
    @Override
    public void stopTask(UUID jobId, Duration grace) {
        requestStop(jobId, "Stop requested by user", grace, false);
    }

    /**
     * Remove a task: unschedules it and attempts to stop the current run with the default grace period.
     */
    @Override
    public boolean removeTask(UUID jobId) {
        return removeTask(jobId, Duration.ofMillis(defaultGraceMillis));
    }

    @Override
    public boolean removeTask(UUID jobId, Duration grace) {
        JobRecord rec = jobs.get(jobId);
        if (rec == null) return false;
        rec.removed.set(true);
        requestStop(jobId, "Removed", grace, true);
        jobs.remove(jobId);
        return true;
    }

    /**
     * Get the state/diagnostics for a task.
     */
    @Override
    public Optional<JobInfo> query(UUID jobId) {
        JobRecord r = jobs.get(jobId);
        if (r == null) return Optional.empty();
        return Optional.of(new JobInfo(r.id, r.state, r.lastStart, r.lastEnd, r.lastError));
    }

    /**
     * List all tasks.
     */
    @Override
    public List<JobInfo> listTasks() {
        List<JobInfo> out = new ArrayList<>();
        for (JobRecord r : jobs.values()) out.add(new JobInfo(r.id, r.state, r.lastStart, r.lastEnd, r.lastError));
        return out;
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        // сначала мягко попросим остановиться все задачи
        for (UUID id : new ArrayList<>(jobs.keySet())) {
            try {
                requestStop(id, "Scheduler closing", Duration.ofMillis(defaultGraceMillis), true);
            } catch (Exception ignored) {
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // ======== Internals ========

    private void submitRun(JobRecord rec) {
        CancellationToken token = new CancellationToken();
        rec.tokenRef.set(token);

        Runnable wrapper = () -> {
            rec.lastStart = Instant.now();
            rec.state = JobState.RUNNING;
            fire(l -> l.onStart(rec.id));
            try {
                rec.task.run(token);

                // Если во время ожидания grace произошёл timeout и мы уже выставили TIMEOUT,
                // не перетираем его на CANCELLED/COMPLETED и не шлём вторичные события.
                if (rec.state == JobState.TIMEOUT) {
                    rec.lastEnd = Instant.now();
                    return;
                }

                boolean cancelled = token.isStopRequested();
                rec.state = cancelled ? JobState.CANCELLED : JobState.COMPLETED;
                rec.lastEnd = Instant.now();
                if (cancelled) {
                    fire(l -> l.onCancelled(rec.id));
                } else {
                    fire(l -> l.onComplete(rec.id));
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();

                if (rec.state != JobState.TIMEOUT) {
                    rec.state = JobState.CANCELLED;
                    rec.lastEnd = Instant.now();
                    fire(l -> l.onCancelled(rec.id));
                } else {
                    rec.lastEnd = Instant.now(); // фиксируем конец при TIMEOUT
                }

            } catch (CancellationException ce) {
                if (rec.state != JobState.TIMEOUT) {
                    rec.state = JobState.CANCELLED;
                    rec.lastEnd = Instant.now();
                    fire(l -> l.onCancelled(rec.id));
                } else {
                    rec.lastEnd = Instant.now();
                }

            } catch (Throwable ex) {
                // Ошибка важнее обычной отмены, но если уже TIMEOUT — оставляем TIMEOUT
                if (rec.state != JobState.TIMEOUT) {
                    rec.state = JobState.FAILED;
                    rec.lastEnd = Instant.now();
                    rec.lastError = String.valueOf(ex);
                    fire(l -> l.onError(rec.id, ex));
                } else {
                    rec.lastEnd = Instant.now();
                }

            } finally {
                rec.runningFuture = null;
                rec.tokenRef.set(null);
            }
        };

        Future<?> f = executor.submit(wrapper);
        rec.runningFuture = f;
    }

    private void requestStop(UUID jobId, String reason, Duration grace, boolean markRemovedIfDone) {
        JobRecord rec = jobs.get(jobId);
        if (rec == null) return;

        Future<?> f = rec.runningFuture;
        CancellationToken token = rec.tokenRef.get();

        if (f != null && !f.isDone()) {
            try {
                rec.task.onStopRequested();
            } catch (Throwable ignored) {
            }
            if (token != null) token.requestStop(reason);

            try {
                f.get(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
                // Успели завершиться кооперативно: событие и финальный статус выставляет submitRun().
                if (markRemovedIfDone) rec.state = JobState.CANCELLED;

            } catch (TimeoutException te) {
                // Единственная точка, где выставляем TIMEOUT и шлём onTimeout.
                f.cancel(true); // interrupt
                rec.state = JobState.TIMEOUT;
                fire(l -> l.onTimeout(rec.id));

            } catch (ExecutionException ee) {
                rec.state = JobState.FAILED;
                rec.lastEnd = Instant.now();
                rec.lastError = String.valueOf(ee.getCause());
                fire(l -> l.onError(rec.id, ee.getCause()));

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                rec.state = JobState.CANCELLED;
                fire(l -> l.onCancelled(rec.id));
            }
        } else {
            // не бежит
            if (markRemovedIfDone) rec.state = JobState.CANCELLED;
        }
    }

    private void fire(Consumer<JobEventListener> c) {
        for (JobEventListener l : listeners) {
            try {
                c.accept(l);
            } catch (Throwable ignored) {
            }
        }
    }
}
