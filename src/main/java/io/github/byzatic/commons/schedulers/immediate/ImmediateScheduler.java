package io.github.byzatic.commons.schedulers.immediate;

import io.github.byzatic.commons.schedulers.unified.RunHandle;
import io.github.byzatic.commons.schedulers.unified.RunOutcome;
import io.github.byzatic.commons.schedulers.unified.RunState;
import io.github.byzatic.commons.schedulers.unified.ScheduleEventListener;
import io.github.byzatic.commons.schedulers.unified.ShutdownPolicy;
import io.github.byzatic.commons.schedulers.unified.UnifiedScheduler;
import io.github.byzatic.commons.schedulers.unified.UnifiedSchedulerInterface;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Legacy immediate-scheduler facade backed by {@link UnifiedScheduler}. */
public final class ImmediateScheduler implements ImmediateSchedulerInterface {
    private final UnifiedSchedulerInterface delegate;
    private final Duration defaultGrace;
    private final CopyOnWriteArrayList<JobEventListener> listeners;
    private final ConcurrentMap<UUID, LegacyRecord> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean ownsDelegate;
    private final LegacyEventBridge eventBridge;

    private ImmediateScheduler(UnifiedSchedulerInterface delegate, Duration defaultGrace,
                               List<JobEventListener> listeners, boolean ownsDelegate) {
        this.delegate = delegate;
        this.defaultGrace = defaultGrace;
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        this.ownsDelegate = ownsDelegate;
        this.eventBridge = new LegacyEventBridge();
        delegate.addListener(eventBridge);
    }

    public static final class Builder {
        private ThreadPoolExecutor executor;
        private Duration defaultGrace = Duration.ofSeconds(10);
        private final List<JobEventListener> listeners = new ArrayList<>();

        public Builder executor(ThreadPoolExecutor executor) { this.executor = Objects.requireNonNull(executor); return this; }
        public Builder defaultGrace(Duration grace) {
            Objects.requireNonNull(grace, "grace");
            if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");
            defaultGrace = grace; return this;
        }
        public Builder addListener(JobEventListener listener) { listeners.add(Objects.requireNonNull(listener)); return this; }
        public ImmediateScheduler build() {
            UnifiedScheduler.Builder builder = UnifiedScheduler.builder()
                    .threadNamePrefix("immediate-exec")
                    .shutdownPolicy(ShutdownPolicy.builder()
                            .gracefulTimeout(defaultGrace).forcedTimeout(Duration.ofSeconds(5)).build());
            if (executor != null) builder.executor(executor);
            return new ImmediateScheduler(builder.build(), defaultGrace, listeners, true);
        }
    }

    /** Creates a facade that does not own or close the supplied unified scheduler. */
    public static ImmediateScheduler adapt(UnifiedSchedulerInterface scheduler) {
        return new ImmediateScheduler(Objects.requireNonNull(scheduler, "scheduler"),
                Duration.ofSeconds(10), List.of(), false);
    }

    @Override public void addListener(JobEventListener listener) { listeners.add(Objects.requireNonNull(listener)); }
    @Override public void removeListener(JobEventListener listener) { listeners.remove(listener); }

    @Override
    public UUID addTask(Task task) {
        Objects.requireNonNull(task, "task");
        RunHandle handle = delegate.submit(new io.github.byzatic.commons.schedulers.unified.ScheduledTask() {
            @Override public void run(io.github.byzatic.commons.schedulers.unified.CancellationContext context) throws Exception {
                task.run(CancellationToken.adapt(context));
            }
            @Override public void onCancellationRequested() { task.onStopRequested(); }
        });
        LegacyRecord record = new LegacyRecord(handle);
        jobs.put(handle.id(), record);
        record.catchUp(handle);
        return handle.id();
    }

    @Override public void stopTask(UUID jobId, Duration grace) {
        LegacyRecord record = jobs.get(jobId);
        if (record == null) return;
        RunHandle handle = record.handle;
        if (handle == null) return;
        try { handle.cancel("Stop requested by user", requireGrace(grace)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Override public boolean removeTask(UUID jobId) { return removeTask(jobId, defaultGrace); }

    @Override
    public boolean removeTask(UUID jobId, Duration grace) {
        LegacyRecord record = jobs.get(jobId);
        if (record == null) return false;
        RunHandle handle = record.handle;
        try { if (handle != null) handle.cancel("Removed", requireGrace(grace)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        jobs.remove(jobId, record);
        return true;
    }

    @Override public Optional<JobInfo> query(UUID jobId) {
        LegacyRecord record = jobs.get(jobId);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    @Override public List<JobInfo> listTasks() {
        List<JobInfo> result = new ArrayList<>();
        for (LegacyRecord record : jobs.values()) result.add(record.snapshot());
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        delegate.removeListener(eventBridge);
        if (ownsDelegate) {
            delegate.close();
            return;
        }
        for (LegacyRecord record : new ArrayList<>(jobs.values())) {
            RunHandle handle = record.handle;
            if (handle == null) continue;
            try { handle.cancel("Immediate facade closing", defaultGrace); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        jobs.clear();
    }

    private Duration requireGrace(Duration grace) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative");
        return grace;
    }

    private final class LegacyEventBridge implements ScheduleEventListener {
        @Override public void onRunStart(UUID scheduleId, UUID runId) {
            LegacyRecord record = jobs.get(runId);
            if (record != null) record.fireStart();
        }
        @Override public void onRunComplete(UUID scheduleId, RunOutcome outcome) {
            LegacyRecord record = jobs.get(outcome.runId());
            if (record != null) record.accept(outcome);
        }
    }

    private final class LegacyRecord {
        private final UUID id;
        private volatile RunHandle handle;
        private final AtomicBoolean startSent = new AtomicBoolean(false);
        private final CompletableFuture<Void> startPublished = new CompletableFuture<>();
        private final AtomicBoolean terminalSent = new AtomicBoolean(false);
        private volatile JobState state = JobState.SCHEDULED;
        private volatile Instant start;
        private volatile Instant end;
        private volatile String error;

        private LegacyRecord(RunHandle handle) { this.id = handle.id(); this.handle = handle; }
        private void catchUp(RunHandle current) {
            if (current.state() != RunState.WAITING && current.state() != RunState.QUEUED) fireStart();
            current.completion().thenAccept(this::accept);
        }
        private void fireStart() {
            if (startSent.compareAndSet(false, true)) {
                try {
                    state = JobState.RUNNING;
                    start = Instant.now();
                    fire(listener -> listener.onStart(id));
                } finally {
                    startPublished.complete(null);
                }
                return;
            }
            startPublished.join();
        }
        private void accept(RunOutcome outcome) {
            fireStart();
            if (!terminalSent.compareAndSet(false, true)) return;
            outcome.startedAt().ifPresent(value -> start = value);
            end = outcome.completedAt();
            error = outcome.failure().map(String::valueOf).orElse(null);
            state = map(outcome.state());
            handle = null;
            if (state == JobState.COMPLETED) fire(listener -> listener.onComplete(id));
            else if (state == JobState.FAILED) fire(listener -> listener.onError(id, outcome.failure().orElse(null)));
            else if (state == JobState.TIMEOUT) fire(listener -> listener.onTimeout(id));
            else if (state == JobState.CANCELLED) fire(listener -> listener.onCancelled(id));
        }
        private JobInfo snapshot() { return new JobInfo(id, state, start, end, error); }
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
