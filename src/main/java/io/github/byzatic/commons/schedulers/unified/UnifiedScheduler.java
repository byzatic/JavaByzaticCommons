package io.github.byzatic.commons.schedulers.unified;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified immediate, delayed, periodic and cron scheduler. All user tasks are executed by a
 * {@link ThreadPoolExecutor}; the timer dispatcher only transfers due work to that executor.
 */
public final class UnifiedScheduler implements UnifiedSchedulerInterface {
    private static final int MAX_TRIGGER_DISPATCH_BATCH = 1_024;

    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final ShutdownPolicy shutdownPolicy;
    private final DelayQueue<TriggerEntry> triggers = new DelayQueue<>();
    private final ConcurrentMap<UUID, ScheduleControl> schedules = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RunControl> activeRuns = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ScheduleEventListener> listeners;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean dispatcherRunning = new AtomicBoolean(true);
    private final AtomicLong triggerSequence = new AtomicLong();
    private final CountDownLatch dispatcherTerminated = new CountDownLatch(1);
    private final Thread dispatcher;

    private UnifiedScheduler(Builder builder) {
        clock = builder.clock;
        shutdownPolicy = builder.shutdownPolicy;
        listeners = new CopyOnWriteArrayList<>(builder.listeners);
        executor = builder.executor == null ? createExecutor(builder) : validateExecutor(builder.executor);
        dispatcher = builder.timerThreadFactory.newThread(this::dispatchLoop);
        dispatcher.start();
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public ExecutionLane serialLane(String name) {
        ensureAccepting();
        return new SerialExecutionLane(this, name);
    }

    @Override
    public RunHandle submit(ScheduledTask task) {
        Objects.requireNonNull(task, "task");
        ensureAccepting();
        RunControl run = new RunControl(null, task, RunState.QUEUED);
        submitRun(run, true);
        return run;
    }

    @Override
    public RunHandle submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        return submit(cancellation -> task.run());
    }

    @Override
    public RunHandle schedule(ScheduledTask task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Duration validDelay = DelayedSchedule.requireNonNegative(delay, "delay");
        ensureAccepting();
        if (validDelay.isZero()) return submit(task);
        RunControl run = new RunControl(null, task, RunState.WAITING);
        activeRuns.put(run.id(), run);
        TriggerEntry trigger = TriggerEntry.forRun(run, clock.instant().plus(validDelay), nextSequence());
        run.pendingTrigger = trigger;
        offerTrigger(trigger);
        return run;
    }

    @Override
    public ScheduleHandle schedule(ScheduledTask task, Schedule schedule) {
        return schedule(task, schedule, ScheduleOptions.defaults());
    }

    @Override
    public ScheduleHandle schedule(ScheduledTask task, Schedule schedule, ScheduleOptions options) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(options, "options");
        ensureAccepting();
        ScheduleControl control = new ScheduleControl(task, schedule, options);
        schedules.put(control.id(), control);
        try {
            control.scheduleInitial();
        } catch (RuntimeException failure) {
            schedules.remove(control.id(), control);
            control.state.set(ScheduleState.FAILED);
            throw failure;
        }
        return control;
    }

    @Override
    public Optional<ScheduleHandle> findSchedule(UUID scheduleId) {
        return Optional.ofNullable(schedules.get(Objects.requireNonNull(scheduleId, "scheduleId")));
    }

    @Override
    public List<ScheduleHandle> listSchedules() {
        return Collections.unmodifiableList(new ArrayList<ScheduleHandle>(schedules.values()));
    }

    @Override public void addListener(ScheduleEventListener listener) { listeners.add(Objects.requireNonNull(listener)); }
    @Override public void removeListener(ScheduleEventListener listener) { listeners.remove(listener); }

    @Override
    public void shutdown() {
        if (!accepting.compareAndSet(true, false)) return;
        stopDispatcher();
        for (ScheduleControl schedule : schedules.values()) schedule.cancelFutureTriggers();
        for (RunControl run : activeRuns.values()) {
            if (run.state() == RunState.WAITING) run.cancelBeforeExecution("Scheduler shutdown");
        }
        executor.shutdown();
    }

    @Override
    public List<RunHandle> shutdownNow() {
        accepting.set(false);
        stopDispatcher();
        for (ScheduleControl schedule : schedules.values()) schedule.cancelFutureTriggers();
        for (RunControl run : activeRuns.values()) run.forceCancellation("Scheduler forced shutdown", false);
        List<Runnable> queued = executor.shutdownNow();
        List<RunHandle> notStarted = new ArrayList<>();
        for (Runnable runnable : queued) {
            if (runnable instanceof RunControl) {
                RunControl run = (RunControl) runnable;
                run.cancelBeforeExecution("Scheduler forced shutdown");
                notStarted.add(run);
            }
        }
        return Collections.unmodifiableList(notStarted);
    }

    @Override
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if (isCurrentWorkerThread()) {
            throw new IllegalStateException("A scheduler worker cannot await its own termination");
        }
        Duration valid = DelayedSchedule.requireNonNegative(timeout, "timeout");
        long deadline = System.nanoTime() + valid.toNanos();
        long dispatcherWait = Math.max(0L, deadline - System.nanoTime());
        if (!dispatcherTerminated.await(dispatcherWait, TimeUnit.NANOSECONDS)) return false;
        long executorWait = Math.max(0L, deadline - System.nanoTime());
        return executor.awaitTermination(executorWait, TimeUnit.NANOSECONDS);
    }

    @Override public boolean isShutdown() { return !accepting.get(); }
    @Override public boolean isTerminated() { return dispatcherTerminated.getCount() == 0 && executor.isTerminated(); }

    @Override
    public void close() {
        for (RunControl run : activeRuns.values()) run.requestCancellation("Scheduler closing");
        shutdown();
        if (isCurrentWorkerThread()) {
            shutdownNow();
            return;
        }
        try {
            if (!awaitTermination(shutdownPolicy.gracefulTimeout())) {
                shutdownNow();
                awaitTermination(shutdownPolicy.forcedTimeout());
            }
        } catch (InterruptedException interrupted) {
            shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void ensureAccepting() {
        if (!accepting.get()) throw new RejectedExecutionException("Scheduler is shut down");
    }

    private void dispatchLoop() {
        List<TriggerEntry> dueTriggers = new ArrayList<>(MAX_TRIGGER_DISPATCH_BATCH);
        try {
            while (dispatcherRunning.get()) {
                try {
                    TriggerEntry trigger = triggers.take();
                    dispatchTriggerSafely(trigger);
                    triggers.drainTo(dueTriggers, MAX_TRIGGER_DISPATCH_BATCH);
                    for (TriggerEntry dueTrigger : dueTriggers) {
                        if (!dispatcherRunning.get()) break;
                        dispatchTriggerSafely(dueTrigger);
                    }
                } catch (InterruptedException interrupted) {
                    if (!dispatcherRunning.get()) break;
                } finally {
                    dueTriggers.clear();
                }
            }
        } finally {
            dispatcherTerminated.countDown();
        }
    }

    private void dispatchTriggerSafely(TriggerEntry trigger) {
        try {
            if (trigger.run != null) dispatchDelayedRun(trigger.run);
            else if (trigger.schedule != null) trigger.schedule.onTrigger(trigger);
        } catch (Throwable ignored) {
            // A malformed schedule must not terminate timing for unrelated schedules.
        }
    }

    private void dispatchDelayedRun(RunControl run) {
        run.pendingTrigger = null;
        if (!accepting.get()) {
            run.cancelBeforeExecution("Scheduler shutdown before delayed execution");
            return;
        }
        if (!run.state.compareAndSet(RunState.WAITING, RunState.QUEUED)) return;
        submitRun(run, false);
    }

    private void submitRun(RunControl run, boolean propagateRejection) {
        activeRuns.put(run.id(), run);
        try {
            executor.execute(run);
        } catch (RejectedExecutionException rejection) {
            run.reject(rejection);
            if (propagateRejection) throw rejection;
        }
    }

    private void offerTrigger(TriggerEntry trigger) {
        if (!accepting.get()) throw new RejectedExecutionException("Scheduler is shut down");
        triggers.offer(trigger);
    }

    private long nextSequence() { return triggerSequence.incrementAndGet(); }

    private static UUID newIdentifier() {
        // Scheduler identifiers are correlation keys, not security tokens. Thread-local random
        // generation avoids the SecureRandom contention and digest allocation of UUID.randomUUID().
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificantBits = random.nextLong();
        long leastSignificantBits = random.nextLong();
        mostSignificantBits = (mostSignificantBits & 0xffffffffffff0fffL) | 0x0000000000004000L;
        leastSignificantBits = (leastSignificantBits & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private boolean isCurrentWorkerThread() {
        Thread current = Thread.currentThread();
        for (RunControl run : activeRuns.values()) {
            if (run.runner == current) return true;
        }
        return false;
    }

    private void stopDispatcher() {
        if (dispatcherRunning.compareAndSet(true, false)) {
            triggers.clear();
            dispatcher.interrupt();
        }
    }

    private void fireStart(UUID scheduleId, UUID runId) {
        for (ScheduleEventListener listener : listeners) {
            try { listener.onRunStart(scheduleId, runId); } catch (Throwable ignored) { }
        }
    }

    private void fireOutcome(UUID scheduleId, RunOutcome outcome) {
        for (ScheduleEventListener listener : listeners) {
            try { listener.onRunComplete(scheduleId, outcome); } catch (Throwable ignored) { }
        }
    }

    private void fireRejected(UUID scheduleId, UUID runId, Throwable failure) {
        for (ScheduleEventListener listener : listeners) {
            try { listener.onRunRejected(scheduleId, runId, failure); } catch (Throwable ignored) { }
        }
    }

    private void fireSkipped(UUID scheduleId) {
        for (ScheduleEventListener listener : listeners) {
            try { listener.onRunSkipped(scheduleId); } catch (Throwable ignored) { }
        }
    }

    private static ThreadPoolExecutor createExecutor(Builder builder) {
        ThreadPoolExecutor result = new ThreadPoolExecutor(
                builder.parallelism, builder.parallelism,
                builder.keepAlive.toMillis(), TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(builder.queueCapacity),
                builder.workerThreadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        result.allowCoreThreadTimeOut(builder.allowCoreThreadTimeout);
        return result;
    }

    private static ThreadPoolExecutor validateExecutor(ThreadPoolExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        if (executor.isShutdown()) throw new IllegalArgumentException("executor is already shut down");
        if (!(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy)) {
            throw new IllegalArgumentException("executor must use ThreadPoolExecutor.AbortPolicy");
        }
        return executor;
    }

    private final class RunControl implements RunHandle, Runnable {
        private final UUID id = newIdentifier();
        private final ScheduleControl owner;
        private final ScheduledTask task;
        private final CancellationContext cancellation = new CancellationContext();
        private final AtomicReference<RunState> state;
        private final AtomicBoolean cancellationCallbackSent = new AtomicBoolean(false);
        private final AtomicBoolean outcomePublished = new AtomicBoolean(false);
        private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        private volatile Thread runner;
        private volatile TriggerEntry pendingTrigger;
        private volatile Instant startedAt;

        private RunControl(ScheduleControl owner, ScheduledTask task, RunState initialState) {
            this.owner = owner;
            this.task = task;
            this.state = new AtomicReference<>(initialState);
        }

        @Override public UUID id() { return id; }
        @Override public Optional<UUID> scheduleId() { return owner == null ? Optional.empty() : Optional.of(owner.id()); }
        @Override public RunState state() { return state.get(); }

        @Override
        public void run() {
            if (!state.compareAndSet(RunState.QUEUED, RunState.RUNNING)) {
                actualExecutionFinished();
                return;
            }
            runner = Thread.currentThread();
            startedAt = clock.instant();
            fireStart(owner == null ? null : owner.id(), id);
            try {
                task.run(cancellation);
                publish(cancellation.isCancellationRequested() ? RunState.CANCELLED : RunState.COMPLETED, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                publish(RunState.CANCELLED, interrupted);
            } catch (Throwable failure) {
                publish(RunState.FAILED, failure);
            } finally {
                runner = null;
                actualExecutionFinished();
            }
        }

        @Override
        public boolean requestCancellation(String reason) {
            Objects.requireNonNull(reason, "reason");
            RunState current = state.get();
            if (isTerminal(current)) return false;
            cancellation.requestCancellation(reason);
            notifyCancellationCallback();
            if (state.compareAndSet(RunState.WAITING, RunState.CANCELLED)
                    || state.compareAndSet(RunState.QUEUED, RunState.CANCELLED)) {
                TriggerEntry trigger = pendingTrigger;
                if (trigger != null) triggers.remove(trigger);
                pendingTrigger = null;
                executor.remove(this);
                publishExistingState(null);
                actualExecutionFinished();
            }
            return true;
        }

        @Override
        public boolean cancel(String reason, Duration grace) throws InterruptedException {
            Duration validGrace = DelayedSchedule.requireNonNegative(grace, "grace");
            if (!requestCancellation(reason)) return false;
            if (state.get() == RunState.RUNNING) {
                if (runner == Thread.currentThread()) return true;
                try {
                    completion.get(validGrace.toNanos(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException timeout) {
                    forceCancellation(reason, true);
                } catch (ExecutionException impossible) {
                    // Completion always contains RunOutcome rather than completing exceptionally.
                }
            }
            return true;
        }

        private void forceCancellation(String reason, boolean timedOut) {
            requestCancellation(reason);
            RunState terminal = timedOut ? RunState.TIMED_OUT : RunState.CANCELLED;
            if (state.compareAndSet(RunState.RUNNING, terminal)) {
                publishExistingState(null);
                Thread currentRunner = runner;
                if (currentRunner != null) currentRunner.interrupt();
            }
        }

        private void cancelBeforeExecution(String reason) {
            requestCancellation(reason);
        }

        private void reject(Throwable failure) {
            RunState current = state.get();
            if ((current == RunState.QUEUED || current == RunState.WAITING)
                    && state.compareAndSet(current, RunState.REJECTED)) {
                publishExistingState(failure);
                fireRejected(owner == null ? null : owner.id(), id, failure);
                actualExecutionFinished();
            }
        }

        private void notifyCancellationCallback() {
            if (cancellationCallbackSent.compareAndSet(false, true)) {
                try { task.onCancellationRequested(); } catch (Throwable ignored) { }
            }
        }

        private void publish(RunState terminalState, Throwable failure) {
            if (state.compareAndSet(RunState.RUNNING, terminalState)) publishExistingState(failure);
        }

        private void publishExistingState(Throwable failure) {
            if (!outcomePublished.compareAndSet(false, true)) return;
            RunOutcome outcome = new RunOutcome(id, state.get(), startedAt, clock.instant(), failure);
            completion.complete(outcome);
            if (owner != null) owner.onOutcome(outcome);
            fireOutcome(owner == null ? null : owner.id(), outcome);
        }

        private void actualExecutionFinished() {
            activeRuns.remove(id, this);
            if (owner != null) owner.onRunExited(this);
        }

        @Override
        public RunOutcome await() throws InterruptedException, ExecutionException {
            return throwIfFailed(completion.get());
        }

        @Override
        public RunOutcome await(Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            Duration valid = DelayedSchedule.requireNonNegative(timeout, "timeout");
            return throwIfFailed(completion.get(valid.toNanos(), TimeUnit.NANOSECONDS));
        }

        private RunOutcome throwIfFailed(RunOutcome outcome) throws ExecutionException {
            if (outcome.state() == RunState.FAILED || outcome.state() == RunState.REJECTED) {
                throw new ExecutionException(outcome.failure().orElse(null));
            }
            return outcome;
        }

        @Override public CompletionStage<RunOutcome> completion() { return completion; }
    }

    private final class ScheduleControl implements ScheduleHandle {
        private final UUID id = newIdentifier();
        private final ScheduledTask task;
        private final Schedule schedule;
        private final ScheduleOptions options;
        private final AtomicReference<ScheduleState> state = new AtomicReference<>(ScheduleState.ACTIVE);
        private final ConcurrentMap<UUID, RunControl> runs = new ConcurrentHashMap<>();
        private final AtomicBoolean coalesced = new AtomicBoolean(false);
        private final AtomicLong generation = new AtomicLong();
        private volatile Instant nextExecution;
        private volatile RunOutcome lastOutcome;

        private ScheduleControl(ScheduledTask task, Schedule schedule, ScheduleOptions options) {
            this.task = task; this.schedule = schedule; this.options = options;
        }

        @Override public UUID id() { return id; }
        @Override public Schedule schedule() { return schedule; }
        @Override public ScheduleState state() { return state.get(); }
        @Override public Optional<Instant> nextExecutionTime() { return Optional.ofNullable(nextExecution); }
        @Override public Optional<RunOutcome> lastOutcome() { return Optional.ofNullable(lastOutcome); }
        @Override public List<RunHandle> activeRuns() { return Collections.unmodifiableList(new ArrayList<RunHandle>(runs.values())); }

        private void scheduleInitial() {
            Instant now = clock.instant();
            Instant first;
            if (schedule instanceof ImmediateSchedule) first = now;
            else if (schedule instanceof DelayedSchedule) first = now.plus(((DelayedSchedule) schedule).delay());
            else if (schedule instanceof FixedDelaySchedule) first = now.plus(((FixedDelaySchedule) schedule).initialDelay());
            else if (schedule instanceof FixedRateSchedule) first = now.plus(((FixedRateSchedule) schedule).initialDelay());
            else if (schedule instanceof CronSchedule) {
                CronSchedule cron = (CronSchedule) schedule;
                first = cron.runImmediately() ? now : cron.expression().next(now, cron.zone())
                        .orElseThrow(() -> new IllegalArgumentException("Cron has no future fire time: " + cron.expression()));
            } else throw new IllegalArgumentException("Unsupported schedule type: " + schedule.getClass().getName());
            scheduleAt(first);
        }

        private void scheduleAt(Instant instant) {
            if (state.get() != ScheduleState.ACTIVE || !accepting.get()) return;
            nextExecution = instant;
            TriggerEntry trigger = TriggerEntry.forSchedule(this, generation.get(), instant, nextSequence());
            triggers.offer(trigger);
            if (!accepting.get()) {
                triggers.remove(trigger);
                nextExecution = null;
            }
        }

        private void onTrigger(TriggerEntry trigger) {
            if (state.get() != ScheduleState.ACTIVE || trigger.generation != generation.get()) return;
            nextExecution = null;
            boolean occupied = !runs.isEmpty();
            if (occupied && options.overlapPolicy() == OverlapPolicy.SKIP) {
                fireSkipped(id);
                scheduleNext(trigger.scheduledAt);
                return;
            }
            if (occupied && options.overlapPolicy() == OverlapPolicy.COALESCE) {
                coalesced.set(true);
                scheduleNext(trigger.scheduledAt);
                return;
            }
            RunControl run = new RunControl(this, task, RunState.QUEUED);
            runs.put(run.id(), run);
            submitRun(run, false);
            scheduleNext(trigger.scheduledAt);
        }

        private void scheduleNext(Instant previousScheduledAt) {
            if (state.get() != ScheduleState.ACTIVE) return;
            Instant now = clock.instant();
            if (schedule instanceof FixedDelaySchedule) return;
            if (schedule instanceof FixedRateSchedule) {
                Instant next = previousScheduledAt.plus(((FixedRateSchedule) schedule).period());
                if (next.isBefore(now)) {
                    next = options.misfirePolicy() == MisfirePolicy.FIRE_ONCE_NOW
                            ? now : now.plus(((FixedRateSchedule) schedule).period());
                }
                scheduleAt(next);
            } else if (schedule instanceof CronSchedule) {
                CronSchedule cron = (CronSchedule) schedule;
                Instant base = previousScheduledAt.isBefore(now)
                        && options.misfirePolicy() == MisfirePolicy.SKIP ? now : previousScheduledAt;
                Instant next = cron.expression().next(base, cron.zone()).orElse(null);
                if (next != null) scheduleAt(next);
            }
        }

        private void onOutcome(RunOutcome outcome) {
            lastOutcome = outcome;
            if (outcome.state() == RunState.FAILED) {
                if (options.failurePolicy() == FailurePolicy.PAUSE_SCHEDULE) pause();
                else if (options.failurePolicy() == FailurePolicy.CANCEL_SCHEDULE) cancel();
            }
        }

        private void onRunExited(RunControl run) {
            runs.remove(run.id(), run);
            if (state.get() != ScheduleState.ACTIVE) return;
            if (schedule instanceof ImmediateSchedule || schedule instanceof DelayedSchedule) {
                state.compareAndSet(ScheduleState.ACTIVE, ScheduleState.COMPLETED);
                schedules.remove(id, this);
            } else if (schedule instanceof FixedDelaySchedule) {
                scheduleAt(clock.instant().plus(((FixedDelaySchedule) schedule).delay()));
            } else if (coalesced.compareAndSet(true, false)) {
                scheduleAt(clock.instant());
            }
        }

        @Override
        public boolean pause() {
            if (!state.compareAndSet(ScheduleState.ACTIVE, ScheduleState.PAUSED)) return false;
            generation.incrementAndGet(); removePendingTriggers(); nextExecution = null; return true;
        }

        @Override
        public boolean resume() {
            if (!accepting.get() || !state.compareAndSet(ScheduleState.PAUSED, ScheduleState.ACTIVE)) return false;
            generation.incrementAndGet(); scheduleInitial(); return true;
        }

        @Override public boolean cancel() {
            try { return cancel(options.cancellationGrace()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return true; }
        }

        @Override
        public boolean cancel(Duration grace) throws InterruptedException {
            ScheduleState previous = state.getAndSet(ScheduleState.CANCELLED);
            if (previous == ScheduleState.CANCELLED || previous == ScheduleState.COMPLETED) return false;
            generation.incrementAndGet(); removePendingTriggers(); nextExecution = null;
            stopActiveRuns(grace); schedules.remove(id, this); return true;
        }

        @Override
        public boolean stopActiveRuns(Duration grace) throws InterruptedException {
            boolean changed = false;
            for (RunControl run : new ArrayList<>(runs.values())) {
                changed |= run.cancel("Schedule stop requested", grace);
            }
            return changed;
        }

        private void cancelFutureTriggers() {
            ScheduleState current = state.get();
            if (current == ScheduleState.ACTIVE || current == ScheduleState.PAUSED) state.set(ScheduleState.CANCELLED);
            generation.incrementAndGet(); removePendingTriggers(); nextExecution = null; schedules.remove(id, this);
        }

        private void removePendingTriggers() {
            triggers.removeIf(trigger -> trigger.schedule == this);
        }
    }

    private static boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED || state == RunState.FAILED || state == RunState.CANCELLED
                || state == RunState.TIMED_OUT || state == RunState.REJECTED || state == RunState.SKIPPED;
    }

    private static final class TriggerEntry implements Delayed {
        private final RunControl run;
        private final ScheduleControl schedule;
        private final long generation;
        private final Instant scheduledAt;
        private final long triggerAtMillis;
        private final long sequence;

        private TriggerEntry(RunControl run, ScheduleControl schedule, long generation,
                             Instant scheduledAt, long sequence) {
            this.run = run; this.schedule = schedule; this.generation = generation;
            this.scheduledAt = scheduledAt; this.triggerAtMillis = scheduledAt.toEpochMilli();
            this.sequence = sequence;
        }

        static TriggerEntry forRun(RunControl run, Instant at, long sequence) {
            return new TriggerEntry(run, null, 0L, at, sequence);
        }
        static TriggerEntry forSchedule(ScheduleControl schedule, long generation, Instant at, long sequence) {
            return new TriggerEntry(null, schedule, generation, at, sequence);
        }
        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(triggerAtMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public int compareTo(Delayed other) {
            TriggerEntry that = (TriggerEntry) other;
            int byTime = Long.compare(triggerAtMillis, that.triggerAtMillis);
            return byTime != 0 ? byTime : Long.compare(sequence, that.sequence);
        }
    }

    public static final class Builder {
        private int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int queueCapacity = 10_000;
        private Duration keepAlive = Duration.ofSeconds(60);
        private boolean allowCoreThreadTimeout = true;
        private Clock clock = Clock.systemUTC();
        private ShutdownPolicy shutdownPolicy = ShutdownPolicy.defaults();
        private ThreadPoolExecutor executor;
        private ThreadFactory workerThreadFactory = new NamedThreadFactory("unified-exec", false);
        private ThreadFactory timerThreadFactory = new NamedThreadFactory("unified-timer", true);
        private final List<ScheduleEventListener> listeners = new ArrayList<>();
        private Builder() { }

        public Builder singleThreaded() { parallelism = 1; return this; }
        public Builder parallelism(int value) {
            if (value <= 0) throw new IllegalArgumentException("parallelism must be greater than zero");
            parallelism = value; return this;
        }
        public Builder queueCapacity(int value) {
            if (value <= 0) throw new IllegalArgumentException("queueCapacity must be greater than zero");
            queueCapacity = value; return this;
        }
        public Builder keepAlive(Duration value) { keepAlive = DelayedSchedule.requireNonNegative(value, "keepAlive"); return this; }
        public Builder allowCoreThreadTimeout(boolean value) { allowCoreThreadTimeout = value; return this; }
        public Builder threadNamePrefix(String value) {
            workerThreadFactory = new NamedThreadFactory(requireName(value), false); return this;
        }
        public Builder timerThreadName(String value) {
            timerThreadFactory = new NamedThreadFactory(requireName(value), true); return this;
        }
        public Builder threadFactory(ThreadFactory value) { workerThreadFactory = Objects.requireNonNull(value); return this; }
        public Builder timerThreadFactory(ThreadFactory value) { timerThreadFactory = Objects.requireNonNull(value); return this; }
        public Builder clock(Clock value) { clock = Objects.requireNonNull(value); return this; }
        public Builder shutdownPolicy(ShutdownPolicy value) { shutdownPolicy = Objects.requireNonNull(value); return this; }
        public Builder executor(ThreadPoolExecutor value) { executor = Objects.requireNonNull(value); return this; }
        public Builder addListener(ScheduleEventListener value) { listeners.add(Objects.requireNonNull(value)); return this; }
        public UnifiedScheduler build() { return new UnifiedScheduler(this); }

        private static String requireName(String value) {
            Objects.requireNonNull(value, "threadName");
            if (value.isBlank()) throw new IllegalArgumentException("threadName must not be blank");
            return value;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final boolean daemon;
        private final AtomicLong sequence = new AtomicLong();
        private NamedThreadFactory(String prefix, boolean daemon) { this.prefix = prefix; this.daemon = daemon; }
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(daemon); return thread;
        }
    }
}
