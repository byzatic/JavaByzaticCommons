package io.github.byzatic.commons.schedulers.cron;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * CronScheduler
 * - Scheduling via cron (5 or 6 fields: [sec] min hour dom mon dow)
 * - Configurable ThreadPoolExecutor
 * - Add/remove tasks at runtime
 * - Soft task stop via CancellationToken + termination on timeout
 * - Event subscription (start/complete/error/timeout/cancelled)
 * - Prevent parallel execution of the same task (disallowOverlap)
 */
public final class CronScheduler implements CronSchedulerInterface {
    private final ThreadPoolExecutor executor;
    private final ZoneId zone;
    private final long defaultGraceMillis;
    private final List<JobEventListener> listeners;
    private final DelayQueue<ScheduledEntry> queue = new DelayQueue<>();
    private final Map<UUID, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Thread dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public CronScheduler(ThreadPoolExecutor executor, ZoneId zone, long defaultGraceMillis, List<JobEventListener> listeners) {
        this.executor = executor;
        this.zone = zone;
        this.defaultGraceMillis = defaultGraceMillis;
        this.listeners = new CopyOnWriteArrayList<>(listeners);

        this.dispatcher = new Thread(this::dispatchLoop, "cron-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    public static final class Builder {
        private ThreadPoolExecutor executor;
        private ZoneId zone = ZoneId.systemDefault();
        private long defaultGraceMillis = 10_000; // 10s
        private final List<JobEventListener> listeners = new CopyOnWriteArrayList<>();

        /**
         * Provide your own custom thread pool.
         */
        public Builder executor(ThreadPoolExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder zone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone);
            return this;
        }

        /**
         * Default grace period when stopping tasks.
         */
        public Builder defaultGrace(Duration grace) {
            this.defaultGraceMillis = Objects.requireNonNull(grace).toMillis();
            return this;
        }

        public Builder addListener(JobEventListener l) {
            listeners.add(l);
            return this;
        }

        public CronScheduler build() {
            if (executor == null) {
                // дефолтный пул если не задан
                executor = new ThreadPoolExecutor(
                        Math.max(2, Runtime.getRuntime().availableProcessors()),
                        Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                        60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "cron-exec-" + UUID.randomUUID());
                            t.setDaemon(false);
                            t.setUncaughtExceptionHandler((th, ex) ->
                                    System.err.println("[CronScheduler] Uncaught in " + th.getName() + ": " + ex));
                            return t;
                        },
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );
                executor.allowCoreThreadTimeOut(true);
            }
            return new CronScheduler(executor, zone, defaultGraceMillis, listeners);
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

    @Override
    public void removeListener(JobEventListener l) {
        listeners.remove(l);
    }

    /**
     * Add a task with a cron schedule and optionally start it immediately.
     */
    @Override
    public UUID addJob(String cron, CronTask task, boolean disallowOverlap, boolean runImmediately) {
        Objects.requireNonNull(cron);
        Objects.requireNonNull(task);
        CronExpr expr = CronExpr.parse(cron);
        UUID id = UUID.randomUUID();
        JobRecord rec = new JobRecord(id, expr, task, zone, disallowOverlap);
        jobs.put(id, rec);

        long firstTrigger = runImmediately
                ? System.currentTimeMillis()                       // запустить немедленно
                : expr.next(Instant.now(), zone)                   // как раньше: ближайший по cron
                .orElseThrow(() -> new IllegalArgumentException("Cron has no future fire time: " + cron))
                .toEpochMilli();

        queue.offer(new ScheduledEntry(id, firstTrigger));
        return id;
    }

    /**
     * Add a task with a cron schedule. Returns the job's UUID.
     */
    @Override
    public UUID addJob(String cron, CronTask task) {
        return addJob(cron, task, false, true);    // без overlap, НО старт сразу
    }

    /**
     * Add a task with a cron schedule and an option to prevent parallel executions.
     */
    @Override
    public UUID addJob(String cron, CronTask task, boolean disallowOverlap) {
        return addJob(cron, task, disallowOverlap, true); // старт сразу
    }

    /**
     * Remove a task: unschedules it and attempts to stop the current run with the default grace period.
     */
    @Override
    public boolean removeJob(UUID jobId) {
        return removeJob(jobId, Duration.ofMillis(defaultGraceMillis));
    }

    @Override
    public boolean removeJob(UUID jobId, Duration grace) {
        JobRecord rec = jobs.get(jobId);
        if (rec == null) return false;
        rec.removed.set(true);
        requestStop(jobId, "Removed", grace, true);
        jobs.remove(jobId);
        return true;
    }

    /**
     * Soft stop command for the current run, followed by termination on timeout.
     */
    @Override
    public void stopJob(UUID jobId, Duration grace) {
        requestStop(jobId, "Stop requested by user", grace, false);
    }

    /**
     * Get the state/diagnostics for a task.
     */
    @Override
    public Optional<JobInfo> query(UUID jobId) {
        JobRecord r = jobs.get(jobId);
        if (r == null) return Optional.empty();
        return Optional.of(new JobInfo(r.id, r.cron.toString(), r.state, r.lastStart, r.lastEnd, r.lastError));
    }

    /**
     * List all tasks.
     */
    @Override
    public List<JobInfo> listJobs() {
        List<JobInfo> out = new ArrayList<>();
        for (JobRecord r : jobs.values()) {
            out.add(new JobInfo(r.id, r.cron.toString(), r.state, r.lastStart, r.lastEnd, r.lastError));
        }
        return out;
    }

    @Override
    public void close() {
        running.set(false);
        dispatcher.interrupt();
        // Остановим все задачи мягко
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

    // ======== Internal ========

    private void dispatchLoop() {
        while (running.get()) {
            try {
                ScheduledEntry entry = queue.take(); // блокируется до наступления времени
                JobRecord rec = jobs.get(entry.jobId);
                if (rec == null || rec.removed.get()) continue;

                // Если запрещены параллельные запуски и задача ещё бежит — пропускаем этот тик и перепланируем
                if (rec.disallowOverlap && rec.isRunning.get()) {
                    Instant nextIfSkipped = rec.cron.next(Instant.now(), rec.zone).orElse(null);
                    if (nextIfSkipped != null && !rec.removed.get()) {
                        queue.offer(new ScheduledEntry(rec.id, nextIfSkipped.toEpochMilli()));
                    }
                    continue;
                }

                // Планируем выполнение
                submitRun(rec);

                // Пере-планируем следующий запуск
                Instant next = rec.cron.next(Instant.now(), rec.zone).orElse(null);
                if (next != null && !rec.removed.get()) {
                    queue.offer(new ScheduledEntry(rec.id, next.toEpochMilli()));
                }
            } catch (InterruptedException ie) {
                if (!running.get()) break;
            } catch (Throwable t) {
                System.err.println("[CronScheduler] dispatcher error: " + t);
            }
        }
    }

    private void submitRun(JobRecord rec) {
        if (rec.disallowOverlap && !rec.isRunning.compareAndSet(false, true)) return;

        // сбрасываем флаги перед запуском
        rec.timedOut.set(false);
        rec.terminalEventSent.set(false);

        CancellationToken token = new CancellationToken();
        rec.tokenRef.set(token);

        Runnable wrapper = () -> {
            rec.lastStart = Instant.now();
            rec.state = JobState.RUNNING;
            fire(l -> l.onStart(rec.id));
            try {
                rec.task.run(token);

                // если уже отметили TIMEOUT – фиксируем конец и выходим без вторичных событий
                if (rec.timedOut.get() || rec.state == JobState.TIMEOUT) {
                    rec.lastEnd = Instant.now();
                    return;
                }

                boolean cancelled = token.isStopRequested();
                rec.state = cancelled ? JobState.CANCELLED : JobState.COMPLETED;
                rec.lastEnd = Instant.now();
                if (cancelled) {
                    fireCancelled(rec);
                } else {
                    fireComplete(rec);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (rec.timedOut.get() || rec.state == JobState.TIMEOUT) {
                    rec.lastEnd = Instant.now(); // уже TIMEOUT – только фиксируем конец
                } else {
                    rec.state = JobState.CANCELLED;
                    rec.lastEnd = Instant.now();
                    fireCancelled(rec);
                }
            } catch (CancellationException ce) {
                if (rec.timedOut.get() || rec.state == JobState.TIMEOUT) {
                    rec.lastEnd = Instant.now();
                } else {
                    rec.state = JobState.CANCELLED;
                    rec.lastEnd = Instant.now();
                    fireCancelled(rec);
                }
            } catch (Throwable ex) {
                if (rec.timedOut.get() || rec.state == JobState.TIMEOUT) {
                    rec.lastEnd = Instant.now();
                } else {
                    rec.state = JobState.FAILED;
                    rec.lastEnd = Instant.now();
                    rec.lastError = String.valueOf(ex);
                    fireError(rec, ex);
                }
            } finally {
                rec.runningFuture = null;
                rec.tokenRef.set(null);
                if (rec.disallowOverlap) rec.isRunning.set(false);
            }
        };

        Future<?> f = executor.submit(wrapper);
        rec.runningFuture = f;
    }

    private void fire(Consumer<JobEventListener> c) {
        for (JobEventListener l : listeners) {
            try {
                c.accept(l);
            } catch (Throwable ignored) {
            }
        }
    }

    private void requestStop(UUID jobId, String reason, Duration grace, boolean markRemovedIfDone) {
        JobRecord rec = jobs.get(jobId);
        if (rec == null) return;

        Future<?> f = rec.runningFuture;
        CancellationToken token = rec.tokenRef.get();

        if (f != null && !f.isDone()) {
            try { rec.task.onStopRequested(); } catch (Throwable ignored) {}
            if (token != null) token.requestStop(reason);

            try {
                f.get(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
                // кооперативно завершилось: вторичные события/статус выставит submitRun()
                if (markRemovedIfDone) rec.state = JobState.CANCELLED;

            } catch (TimeoutException te) {
                // помечаем таймаут ДО interrupt — чтобы раннер это увидел и не шлёт onCancelled/onComplete
                rec.timedOut.set(true);
                rec.state = JobState.TIMEOUT;
                fireTimeout(rec);

                // теперь прерываем поток задачи
                f.cancel(true);

            } catch (ExecutionException ee) {
                rec.state = JobState.FAILED;
                rec.lastEnd = Instant.now();
                rec.lastError = String.valueOf(ee.getCause());
                fireError(rec, ee.getCause());

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                // если уже TIMEOUT — ничего не перетираем и не шлём onCancelled
                if (!rec.timedOut.get() && rec.state != JobState.TIMEOUT) {
                    rec.state = JobState.CANCELLED;
                    fireCancelled(rec);
                }
            }
        } else {
            // не бежит
            if (markRemovedIfDone) rec.state = JobState.CANCELLED;
        }
    }

    private void fireTimeout(JobRecord rec) {
        if (rec.terminalEventSent.compareAndSet(false, true)) {
            rec.state = JobState.TIMEOUT;
            fire(l -> l.onTimeout(rec.id));
        }
    }

    private void fireCancelled(JobRecord rec) {
        if (rec.terminalEventSent.compareAndSet(false, true)) {
            rec.state = JobState.CANCELLED;
            fire(l -> l.onCancelled(rec.id));
        }
    }

    private void fireComplete(JobRecord rec) {
        if (rec.terminalEventSent.compareAndSet(false, true)) {
            fire(l -> l.onComplete(rec.id));
        }
    }

    private void fireError(JobRecord rec, Throwable ex) {
        if (rec.terminalEventSent.compareAndSet(false, true)) {
            fire(l -> l.onError(rec.id, ex));
        }
    }

}