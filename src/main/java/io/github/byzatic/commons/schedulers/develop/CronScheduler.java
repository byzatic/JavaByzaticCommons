package io.github.byzatic.commons.schedulers.develop;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
public final class CronScheduler implements AutoCloseable {

    // ======== Public API types ========

    /**
     * Статусы жизненного цикла задачи.
     */
    public enum JobState {SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT}

    /**
     * Слушатель событий по джобам.
     */
    public interface JobEventListener {
        default void onStart(UUID jobId) {
        }

        default void onComplete(UUID jobId) {
        }

        default void onError(UUID jobId, Throwable error) {
        }

        default void onTimeout(UUID jobId) {
        }

        default void onCancelled(UUID jobId) {
        }
    }

    /**
     * Интерфейс задачи. Обязательно проверяйте токен!
     */
    public interface CronTask {
        void run(CancellationToken token) throws Exception;

        /**
         * Вызывается при запросе мягкой остановки (опционально).
         */
        default void onStopRequested() {
        }
    }

    /**
     * Токен кооперативной отмены. Создаётся новый на каждый запуск.
     */
    public static final class CancellationToken {
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private volatile String reason = "";

        public boolean isStopRequested() {
            return stop.get();
        }

        public String reason() {
            return reason;
        }

        private void requestStop(String reason) {
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

    /**
     * Информация о задаче (read-only).
     */
    public static final class JobInfo {
        public final UUID id;
        public final String cron;
        public final JobState state;
        public final Instant lastStart;
        public final Instant lastEnd;
        public final String lastError;

        JobInfo(UUID id, String cron, JobState state, Instant lastStart, Instant lastEnd, String lastError) {
            this.id = id;
            this.cron = cron;
            this.state = state;
            this.lastStart = lastStart;
            this.lastEnd = lastEnd;
            this.lastError = lastError;
        }

        @Override
        public String toString() {
            return "JobInfo{id=" + id + ", cron='" + cron + "', state=" + state +
                    ", lastStart=" + lastStart + ", lastEnd=" + lastEnd +
                    (lastError != null ? ", lastError='" + lastError + '\'' : "") + '}';
        }
    }

    // ======== Builder ========

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

    // ======== Internal types ========

    private static final class ScheduledEntry implements Delayed {
        final UUID jobId;
        final long triggerAtMillis;

        ScheduledEntry(UUID jobId, long triggerAtMillis) {
            this.jobId = jobId;
            this.triggerAtMillis = triggerAtMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = triggerAtMillis - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(this.triggerAtMillis, ((ScheduledEntry) o).triggerAtMillis);
        }
    }

    private static final class JobRecord {
        final UUID id;
        final CronExpr cron;
        final CronTask task;
        final ZoneId zone;

        volatile JobState state = JobState.SCHEDULED;
        volatile Instant lastStart = null;
        volatile Instant lastEnd = null;
        volatile String lastError = null;

        volatile Future<?> runningFuture = null;
        final AtomicReference<CancellationToken> tokenRef = new AtomicReference<>();
        final AtomicBoolean removed = new AtomicBoolean(false);
        final AtomicBoolean isRunning = new AtomicBoolean(false); // защита от overlap
        final boolean disallowOverlap;

        JobRecord(UUID id, CronExpr cron, CronTask task, ZoneId zone, boolean disallowOverlap) {
            this.id = id;
            this.cron = cron;
            this.task = task;
            this.zone = zone;
            this.disallowOverlap = disallowOverlap;
        }
    }

    // ======== Fields ========

    private final ThreadPoolExecutor executor;
    private final ZoneId zone;
    private final long defaultGraceMillis;
    private final List<JobEventListener> listeners;

    private final DelayQueue<ScheduledEntry> queue = new DelayQueue<>();
    private final Map<UUID, JobRecord> jobs = new ConcurrentHashMap<>();
    private final Thread dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // ======== Constructor ========

    public CronScheduler(ThreadPoolExecutor executor, ZoneId zone, long defaultGraceMillis, List<JobEventListener> listeners) {
        this.executor = executor;
        this.zone = zone;
        this.defaultGraceMillis = defaultGraceMillis;
        this.listeners = new CopyOnWriteArrayList<>(listeners);

        this.dispatcher = new Thread(this::dispatchLoop, "cron-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    // ======== Public API ========

    /**
     * Зарегистрировать слушателя событий.
     */
    public void addListener(JobEventListener l) {
        listeners.add(Objects.requireNonNull(l));
    }

    public void removeListener(JobEventListener l) {
        listeners.remove(l);
    }

    /**
     * Добавить задачу по cron. Возвращает UUID джобы.
     */
    public UUID addJob(String cron, CronTask task) {
        return addJob(cron, task, false);
    }

    /**
     * Добавить задачу по cron с опцией запрета параллельных запусков.
     */
    public UUID addJob(String cron, CronTask task, boolean disallowOverlap) {
        Objects.requireNonNull(cron);
        Objects.requireNonNull(task);
        CronExpr expr = CronExpr.parse(cron);
        UUID id = UUID.randomUUID();
        JobRecord rec = new JobRecord(id, expr, task, zone, disallowOverlap);
        jobs.put(id, rec);
        // первая дата запуска
        Instant next = expr.next(Instant.now(), zone)
                .orElseThrow(() -> new IllegalArgumentException("Cron has no future fire time: " + cron));
        queue.offer(new ScheduledEntry(id, next.toEpochMilli()));
        return id;
    }

    /**
     * Удалить задачу: снимает с планирования и пытается остановить текущий запуск по умолчанию с grace.
     */
    public boolean removeJob(UUID jobId) {
        return removeJob(jobId, Duration.ofMillis(defaultGraceMillis));
    }

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
    public void stopJob(UUID jobId, Duration grace) {
        requestStop(jobId, "Stop requested by user", grace, false);
    }

    /**
     * Получить состояние/диагностику по задаче.
     */
    public Optional<JobInfo> query(UUID jobId) {
        JobRecord r = jobs.get(jobId);
        if (r == null) return Optional.empty();
        return Optional.of(new JobInfo(r.id, r.cron.toString(), r.state, r.lastStart, r.lastEnd, r.lastError));
    }

    /**
     * Список всех задач.
     */
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
            // уже бежит — защита от гонки
            return;
        }

        // Новый токен для этого запуска
        CancellationToken token = new CancellationToken();
        rec.tokenRef.set(token);

        Runnable wrapper = () -> {
            rec.lastStart = Instant.now();
            rec.state = JobState.RUNNING;
            fire(l -> l.onStart(rec.id));
            try {
                rec.task.run(token);
                rec.state = JobState.COMPLETED;
                rec.lastEnd = Instant.now();
                fire(l -> l.onComplete(rec.id));
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

        // Посылаем мягкую остановку ТОЛЬКО если запуск активен
        if (f != null && !f.isDone()) {
            try {
                rec.task.onStopRequested();
            } catch (Throwable ignored) {
            }
            if (token != null) token.requestStop(reason);

            try {
                f.get(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
                if (markRemovedIfDone) rec.state = JobState.CANCELLED;
                fire(l -> l.onCancelled(rec.id));
            } catch (TimeoutException te) {
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
            // не бежит — просто пометим, если удаляем; событий "cancelled" не шлём
            if (markRemovedIfDone) rec.state = JobState.CANCELLED;
        }
    }

    // ======== CronExpr с поддержкой секунд (6 полей) ========
    static final class CronExpr {
        // Если задано 6 полей: sec min hour dom mon dow
        // Если задано 5 полей:      min hour dom mon dow  (sec=0)
        private final BitSet seconds = new BitSet(60);
        private final BitSet minutes = new BitSet(60);
        private final BitSet hours = new BitSet(24);
        private final BitSet dom = new BitSet(32);  // 1..31
        private final BitSet months = new BitSet(13); // 1..12
        private final BitSet dow = new BitSet(7);  // 0..6 (0=Sunday)

        static CronExpr parse(String s) {
            String[] p = s.trim().split("\\s+");
            if (p.length != 5 && p.length != 6)
                throw new IllegalArgumentException("Cron must have 5 or 6 fields (with seconds): " + s);

            CronExpr ce = new CronExpr();
            int idx = 0;
            if (p.length == 6) {
                ce.parseField(p[idx++], 0, 59, ce.seconds);  // sec
            } else {
                // 5 полей — секунды фиксируем в 0
                ce.seconds.set(0);
            }
            ce.parseField(p[idx++], 0, 59, ce.minutes);      // min
            ce.parseField(p[idx++], 0, 23, ce.hours);        // hour
            ce.parseField(p[idx++], 1, 31, ce.dom);          // day of month
            ce.parseField(p[idx++], 1, 12, ce.months);       // month
            ce.parseField(p[idx], 0, 6, ce.dow);           // day of week (0=Sun)

            if (ce.seconds.isEmpty() || ce.minutes.isEmpty() || ce.hours.isEmpty()
                    || ce.dom.isEmpty() || ce.months.isEmpty() || ce.dow.isEmpty()) {
                throw new IllegalArgumentException("Cron field parsed to empty set: " + s);
            }
            return ce;
        }

        private void parseField(String f, int min, int max, BitSet out) {
            if (f.equals("*")) {
                out.set(min, max + 1);
                return;
            }
            for (String part : f.split(",")) {
                String stepPart = part;
                int step = 1;
                if (part.contains("/")) {
                    String[] ar = part.split("/");
                    stepPart = ar[0];
                    step = Integer.parseInt(ar[1]);
                }
                int start, end;
                if (stepPart.equals("*")) {
                    start = min;
                    end = max;
                } else if (stepPart.contains("-")) {
                    String[] r = stepPart.split("-");
                    start = Integer.parseInt(r[0]);
                    end = Integer.parseInt(r[1]);
                } else {
                    start = end = Integer.parseInt(stepPart);
                }
                if (start < min || end > max || start > end) {
                    throw new IllegalArgumentException("Out of range: " + part);
                }
                for (int v = start; v <= end; v += step) out.set(v);
            }
        }

        Optional<Instant> next(Instant from, ZoneId zone) {
            ZonedDateTime z = ZonedDateTime.ofInstant(from, zone)
                    .plusSeconds(1)
                    .withNano(0);

            // Простой перебор по секундам вперёд (до 2 лет)
            for (int i = 0; i < 366 * 24 * 60 * 60 * 2; i++) {
                if (!months.get(z.getMonthValue())) {
                    z = z.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                    continue;
                }
                if (!dom.get(z.getDayOfMonth())) {
                    z = z.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                    continue;
                }
                if (!dow.get(z.getDayOfWeek().getValue() % 7)) {
                    z = z.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                    continue;
                }
                if (!hours.get(z.getHour())) {
                    z = z.plusHours(1).withMinute(0).withSecond(0);
                    continue;
                }
                if (!minutes.get(z.getMinute())) {
                    z = z.plusMinutes(1).withSecond(0);
                    continue;
                }
                if (!seconds.get(z.getSecond())) {
                    z = z.plusSeconds(1);
                    continue;
                }
                return Optional.of(z.toInstant());
            }
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "CronExpr{...}";
        }
    }
}