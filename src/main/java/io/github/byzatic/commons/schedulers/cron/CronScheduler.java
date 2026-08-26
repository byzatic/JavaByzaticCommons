package io.github.byzatic.commons.schedulers.cron;

import io.github.byzatic.commons.schedulers.unified.CronSchedule;
import io.github.byzatic.commons.schedulers.unified.FailurePolicy;
import io.github.byzatic.commons.schedulers.unified.MisfirePolicy;
import io.github.byzatic.commons.schedulers.unified.OverlapPolicy;
import io.github.byzatic.commons.schedulers.unified.RunHandle;
import io.github.byzatic.commons.schedulers.unified.RunOutcome;
import io.github.byzatic.commons.schedulers.unified.RunState;
import io.github.byzatic.commons.schedulers.unified.ScheduleEventListener;
import io.github.byzatic.commons.schedulers.unified.ScheduleHandle;
import io.github.byzatic.commons.schedulers.unified.ScheduleOptions;
import io.github.byzatic.commons.schedulers.unified.Schedules;
import io.github.byzatic.commons.schedulers.unified.ShutdownPolicy;
import io.github.byzatic.commons.schedulers.unified.UnifiedScheduler;
import io.github.byzatic.commons.schedulers.unified.UnifiedSchedulerInterface;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Legacy cron-scheduler facade backed by {@link UnifiedScheduler}. */
public final class CronScheduler implements CronSchedulerInterface {
    private final UnifiedSchedulerInterface delegate;
    private final ZoneId zone;
    private final Duration defaultGrace;
    private final CopyOnWriteArrayList<JobEventListener> listeners;
    private final ConcurrentMap<UUID, LegacyCronJob> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean ownsDelegate;
    private final LegacyEventBridge eventBridge;

    public CronScheduler(ThreadPoolExecutor executor, ZoneId zone, long defaultGraceMillis,
                         List<JobEventListener> listeners) {
        this(createDelegate(Objects.requireNonNull(executor), Duration.ofMillis(defaultGraceMillis)),
                zone, Duration.ofMillis(defaultGraceMillis), listeners, true);
    }

    private CronScheduler(UnifiedSchedulerInterface delegate, ZoneId zone, Duration defaultGrace,
                          List<JobEventListener> listeners, boolean ownsDelegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.defaultGrace = requireGrace(defaultGrace);
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.ownsDelegate = ownsDelegate;
        this.eventBridge = new LegacyEventBridge();
        delegate.addListener(eventBridge);
    }

    public static final class Builder {
        private ThreadPoolExecutor executor;
        private ZoneId zone = ZoneId.systemDefault();
        private Duration defaultGrace = Duration.ofSeconds(10);
        private final List<JobEventListener> listeners = new ArrayList<>();

        public Builder executor(ThreadPoolExecutor executor) { this.executor = Objects.requireNonNull(executor); return this; }
        public Builder zone(ZoneId zone) { this.zone = Objects.requireNonNull(zone); return this; }
        public Builder defaultGrace(Duration grace) { this.defaultGrace = requireGrace(grace); return this; }
        public Builder addListener(JobEventListener listener) { listeners.add(Objects.requireNonNull(listener)); return this; }
        public CronScheduler build() {
            UnifiedScheduler.Builder builder = UnifiedScheduler.builder()
                    .threadNamePrefix("cron-exec")
                    .timerThreadName("cron-dispatcher")
                    .shutdownPolicy(ShutdownPolicy.builder()
                            .gracefulTimeout(defaultGrace).forcedTimeout(Duration.ofSeconds(5)).build());
            if (executor != null) builder.executor(executor);
            return new CronScheduler(builder.build(), zone, defaultGrace, listeners, true);
        }
    }

    /** Creates a facade that does not own or close the supplied unified scheduler. */
    public static CronScheduler adapt(UnifiedSchedulerInterface scheduler, ZoneId zone,
                                      Duration defaultGrace) {
        return new CronScheduler(Objects.requireNonNull(scheduler, "scheduler"), zone,
                defaultGrace, List.of(), false);
    }

    @Override public void addListener(JobEventListener listener) { listeners.add(Objects.requireNonNull(listener)); }
    @Override public void removeListener(JobEventListener listener) { listeners.remove(listener); }

    @Override
    public UUID addJob(String cron, CronTask task, boolean disallowOverlap, boolean runImmediately) {
        Objects.requireNonNull(cron, "cron");
        Objects.requireNonNull(task, "task");
        CronSchedule cronSchedule = Schedules.cron(cron, zone, runImmediately);
        ScheduleOptions options = ScheduleOptions.builder()
                .overlapPolicy(disallowOverlap ? OverlapPolicy.SKIP : OverlapPolicy.ALLOW)
                .misfirePolicy(MisfirePolicy.SKIP)
                .failurePolicy(FailurePolicy.CONTINUE)
                .cancellationGrace(defaultGrace)
                .build();
        ScheduleHandle handle = delegate.schedule(new io.github.byzatic.commons.schedulers.unified.ScheduledTask() {
            @Override public void run(io.github.byzatic.commons.schedulers.unified.CancellationContext context) throws Exception {
                task.run(CancellationToken.adapt(context));
            }
            @Override public void onCancellationRequested() { task.onStopRequested(); }
        }, cronSchedule, options);
        LegacyCronJob record = new LegacyCronJob(handle, cron);
        jobs.put(handle.id(), record);
        record.catchUp();
        return handle.id();
    }

    @Override public UUID addJob(String cron, CronTask task) { return addJob(cron, task, false, true); }
    @Override public UUID addJob(String cron, CronTask task, boolean disallowOverlap) {
        return addJob(cron, task, disallowOverlap, true);
    }
    @Override public boolean removeJob(UUID jobId) { return removeJob(jobId, defaultGrace); }

    @Override
    public boolean removeJob(UUID jobId, Duration grace) {
        LegacyCronJob record = jobs.get(jobId);
        if (record == null) return false;
        try { record.handle.cancel(requireGrace(grace)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        jobs.remove(jobId, record);
        return true;
    }

    @Override
    public void stopJob(UUID jobId, Duration grace) {
        LegacyCronJob record = jobs.get(jobId);
        if (record == null) return;
        try { record.handle.stopActiveRuns(requireGrace(grace)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Override public Optional<JobInfo> query(UUID jobId) {
        LegacyCronJob record = jobs.get(jobId);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    @Override public List<JobInfo> listJobs() {
        List<JobInfo> result = new ArrayList<>();
        for (LegacyCronJob record : jobs.values()) result.add(record.snapshot());
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        delegate.removeListener(eventBridge);
        if (ownsDelegate) {
            delegate.close();
            return;
        }
        for (LegacyCronJob record : new ArrayList<>(jobs.values())) {
            try { record.handle.cancel(defaultGrace); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        jobs.clear();
    }

    private final class LegacyEventBridge implements ScheduleEventListener {
        @Override public void onRunStart(UUID scheduleId, UUID runId) {
            LegacyCronJob record = jobs.get(scheduleId);
            if (record != null) record.onStart(runId);
        }
        @Override public void onRunComplete(UUID scheduleId, RunOutcome outcome) {
            LegacyCronJob record = jobs.get(scheduleId);
            if (record != null) record.onOutcome(outcome);
        }
    }

    private final class LegacyCronJob {
        private final ScheduleHandle handle;
        private final String cron;
        private final Set<UUID> starts = ConcurrentHashMap.newKeySet();
        private final Set<UUID> terminals = ConcurrentHashMap.newKeySet();
        private volatile JobState state = JobState.SCHEDULED;
        private volatile Instant lastStart;
        private volatile Instant lastEnd;
        private volatile String lastError;

        private LegacyCronJob(ScheduleHandle handle, String cron) { this.handle = handle; this.cron = cron; }
        private void catchUp() {
            for (RunHandle run : handle.activeRuns()) onStart(run.id());
            handle.lastOutcome().ifPresent(this::onOutcome);
        }
        private void onStart(UUID runId) {
            if (!starts.add(runId)) return;
            state = JobState.RUNNING; lastStart = Instant.now(); fire(listener -> listener.onStart(handle.id()));
        }
        private void onOutcome(RunOutcome outcome) {
            if (!terminals.add(outcome.runId())) return;
            outcome.startedAt().ifPresent(value -> lastStart = value);
            lastEnd = outcome.completedAt();
            lastError = outcome.failure().map(String::valueOf).orElse(null);
            state = map(outcome.state());
            if (state == JobState.COMPLETED) fire(listener -> listener.onComplete(handle.id()));
            else if (state == JobState.FAILED) fire(listener -> listener.onError(handle.id(), outcome.failure().orElse(null)));
            else if (state == JobState.TIMEOUT) fire(listener -> listener.onTimeout(handle.id()));
            else if (state == JobState.CANCELLED) fire(listener -> listener.onCancelled(handle.id()));
        }
        private JobInfo snapshot() { return new JobInfo(handle.id(), cron, state, lastStart, lastEnd, lastError); }
    }

    private static UnifiedSchedulerInterface createDelegate(ThreadPoolExecutor executor, Duration grace) {
        return UnifiedScheduler.builder().executor(executor)
                .shutdownPolicy(ShutdownPolicy.builder()
                        .gracefulTimeout(grace).forcedTimeout(Duration.ofSeconds(5)).build())
                .build();
    }

    private static Duration requireGrace(Duration grace) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");
        return grace;
    }

    private static JobState map(RunState state) {
        switch (state) {
            case WAITING: case QUEUED: return JobState.SCHEDULED;
            case RUNNING: return JobState.RUNNING;
            case COMPLETED: return JobState.COMPLETED;
            case FAILED: case REJECTED: return JobState.FAILED;
            case TIMED_OUT: return JobState.TIMEOUT;
            default: return JobState.CANCELLED;
        }
    }

    private void fire(java.util.function.Consumer<JobEventListener> event) {
        for (JobEventListener listener : listeners) {
            try { event.accept(listener); } catch (Throwable ignored) { }
        }
    }
}
