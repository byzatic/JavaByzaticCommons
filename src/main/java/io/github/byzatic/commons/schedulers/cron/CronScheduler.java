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
 * - Планирование по cron (5 или 6 полей: [sec] min hour dom mon dow)
 * - Настраиваемый ThreadPoolExecutor
 * - Добавление/удаление задач в рантайме
 * - Мягкая остановка задач через CancellationToken + kill по таймауту
 * - Подписка на события (start/complete/error/timeout/cancelled)
 * - Запрет параллельных запусков одной задачи (disallowOverlap)
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
         * Передайте свой настраиваемый пул.
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
         * Грейс по умолчанию при остановке задач.
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
     * Зарегистрировать слушателя событий.
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
     * Добавить задачу по cron и (опционально) стартовать её сразу.
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
     * Добавить задачу по cron. Возвращает UUID джобы.
     */
    @Override
    public UUID addJob(String cron, CronTask task) {
        return addJob(cron, task, false, true);    // без overlap, НО старт сразу
    }

    /**
     * Добавить задачу по cron с опцией запрета параллельных запусков.
     */
    @Override
    public UUID addJob(String cron, CronTask task, boolean disallowOverlap) {
        return addJob(cron, task, disallowOverlap, true); // старт сразу
    }

    /**
     * Удалить задачу: снимает с планирования и пытается остановить текущий запуск по умолчанию с grace.
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
     * Команда мягкой остановки текущего запуска с последующим убийством по таймауту.
     */
    @Override
    public void stopJob(UUID jobId, Duration grace) {
        requestStop(jobId, "Stop requested by user", grace, false);
    }

    /**
     * Получить состояние/диагностику по задаче.
     */
    @Override
    public Optional<JobInfo> query(UUID jobId) {
        JobRecord r = jobs.get(jobId);
        if (r == null) return Optional.empty();
        return Optional.of(new JobInfo(r.id, r.cron.toString(), r.state, r.lastStart, r.lastEnd, r.lastError));
    }

    /**
     * Список всех задач.
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
        if (rec.disallowOverlap && !rec.isRunning.compareAndSet(false, true)) {
            return;
        }

        CancellationToken token = new CancellationToken();
        rec.tokenRef.set(token);

        Runnable wrapper = () -> {
            rec.lastStart = Instant.now();
            rec.state = JobState.RUNNING;
            fire(l -> l.onStart(rec.id));
            try {
                rec.task.run(token);
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
                rec.state = JobState.CANCELLED;
                rec.lastEnd = Instant.now();
                fire(l -> l.onCancelled(rec.id));
            } catch (CancellationException ce) {
                rec.state = JobState.CANCELLED;
                rec.lastEnd = Instant.now();
                fire(l -> l.onCancelled(rec.id));
            } catch (Throwable ex) {
                rec.state = JobState.FAILED;
                rec.lastEnd = Instant.now();
                rec.lastError = String.valueOf(ex);
                fire(l -> l.onError(rec.id, ex));
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
            try {
                rec.task.onStopRequested();
            } catch (Throwable ignored) {
            }
            if (token != null) token.requestStop(reason);

            try {
                f.get(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
                // ВАЖНО: событий тут не шлём — это делает раннер.
                if (markRemovedIfDone) rec.state = JobState.CANCELLED;
            } catch (TimeoutException te) {
                f.cancel(true);
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
            // задача не бежит
            if (markRemovedIfDone) rec.state = JobState.CANCELLED;
            // событий тоже не шлём: нечего отменять — запуска не было
        }
    }

}