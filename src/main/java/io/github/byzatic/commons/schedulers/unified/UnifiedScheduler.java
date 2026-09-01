package io.github.byzatic.commons.schedulers.unified;

import io.github.byzatic.commons.schedulers.unified.internal.core.SchedulerRuntimeContext;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SchedulerRun;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SerialExecutionLane;
import io.github.byzatic.commons.schedulers.unified.internal.executor.SchedulerExecutorFactory;
import io.github.byzatic.commons.schedulers.unified.internal.executor.SchedulerThreadFactory;
import io.github.byzatic.commons.schedulers.unified.internal.scheduling.SchedulerSchedule;
import io.github.byzatic.commons.schedulers.unified.internal.timing.SchedulerTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Public composition root for immediate, delayed, periodic and cron execution.
 *
 * <p>Run, schedule, trigger and executor mechanics live in internal implementation modules. User
 * work always executes on a {@link ThreadPoolExecutor}; the timer thread only dispatches due
 * work.</p>
 *
 * <p>Instances are thread-safe. A supplied executor becomes owned by this scheduler.</p>
 */
public final class UnifiedScheduler implements UnifiedSchedulerInterface {
    private static final int MAX_TRIGGER_DISPATCH_BATCH = 1_024;

    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final ShutdownPolicy shutdownPolicy;
    private final DelayQueue<SchedulerTrigger> triggers = new DelayQueue<>();
    private final ConcurrentMap<UUID, SchedulerSchedule> schedules = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, SchedulerRun> activeRuns = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ScheduleEventListener> listeners;
    private final SchedulerRuntimeContext runtimeContext = new RuntimeContext();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean dispatcherRunning = new AtomicBoolean(true);
    private final AtomicLong triggerSequence = new AtomicLong();
    private final CountDownLatch dispatcherTerminated = new CountDownLatch(1);
    private final Thread dispatcher;

    private UnifiedScheduler(Builder builder) {
        clock = builder.clock;
        shutdownPolicy = builder.shutdownPolicy;
        listeners = new CopyOnWriteArrayList<>(builder.listeners);
        executor = builder.executor == null
                ? SchedulerExecutorFactory.create(
                        builder.parallelism,
                        builder.queueCapacity,
                        builder.keepAlive,
                        builder.allowCoreThreadTimeout,
                        builder.workerThreadFactory
                )
                : SchedulerExecutorFactory.validate(builder.executor);
        dispatcher = builder.timerThreadFactory.newThread(this::dispatchLoop);
        dispatcher.start();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ExecutionLane serialLane(String name) {
        synchronized (lifecycleLock) {
            ensureAccepting();
            return new SerialExecutionLane(this, name);
        }
    }

    @Override
    public RunHandle submit(ScheduledTask task) {
        Objects.requireNonNull(task, "task");
        SchedulerRun run = new SchedulerRun(runtimeContext, null, task, RunState.QUEUED);
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
        if (validDelay.isZero()) {
            return submit(task);
        }
        SchedulerRun run = new SchedulerRun(runtimeContext, null, task, RunState.WAITING);
        synchronized (lifecycleLock) {
            ensureAccepting();
            activeRuns.put(run.id(), run);
            SchedulerTrigger trigger = SchedulerTrigger.forRun(
                    run,
                    clock.instant().plus(validDelay),
                    nextTriggerSequence()
            );
            run.pendingTrigger(trigger);
            triggers.offer(trigger);
        }
        return run;
    }

    @Override
    public ScheduleHandle schedule(ScheduledTask task, Schedule schedule) {
        return schedule(task, schedule, ScheduleOptions.defaults());
    }

    @Override
    public ScheduleHandle schedule(
            ScheduledTask task,
            Schedule schedule,
            ScheduleOptions options
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(options, "options");
        SchedulerSchedule control = new SchedulerSchedule(runtimeContext, task, schedule, options);
        synchronized (lifecycleLock) {
            ensureAccepting();
            schedules.put(control.id(), control);
            try {
                control.scheduleInitial();
            } catch (RuntimeException failure) {
                schedules.remove(control.id(), control);
                control.markRegistrationFailed();
                throw failure;
            }
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

    @Override
    public void addListener(ScheduleEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeListener(ScheduleEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (!accepting.compareAndSet(true, false)) {
                return;
            }
        }
        stopDispatcher();
        for (SchedulerSchedule schedule : schedules.values()) {
            schedule.cancelFutureTriggers();
        }
        for (SchedulerRun run : activeRuns.values()) {
            if (run.state() == RunState.WAITING) {
                run.cancelBeforeExecution("Scheduler shutdown");
            }
        }
        executor.shutdown();
    }

    @Override
    public List<RunHandle> shutdownNow() {
        synchronized (lifecycleLock) {
            accepting.set(false);
        }
        stopDispatcher();
        for (SchedulerSchedule schedule : schedules.values()) {
            schedule.cancelFutureTriggers();
        }
        for (SchedulerRun run : activeRuns.values()) {
            run.forceCancellation("Scheduler forced shutdown", false);
        }
        List<Runnable> queued = executor.shutdownNow();
        List<RunHandle> notStarted = new ArrayList<>();
        for (Runnable runnable : queued) {
            if (runnable instanceof SchedulerRun) {
                SchedulerRun run = (SchedulerRun) runnable;
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
        if (!dispatcherTerminated.await(dispatcherWait, TimeUnit.NANOSECONDS)) {
            return false;
        }
        long executorWait = Math.max(0L, deadline - System.nanoTime());
        return executor.awaitTermination(executorWait, TimeUnit.NANOSECONDS);
    }

    @Override
    public boolean isShutdown() {
        return !accepting.get();
    }

    @Override
    public boolean isTerminated() {
        return dispatcherTerminated.getCount() == 0 && executor.isTerminated();
    }

    @Override
    public void close() {
        shutdown();
        for (SchedulerRun run : activeRuns.values()) {
            run.requestCancellation("Scheduler closing");
        }
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

    private Instant now() {
        return clock.instant();
    }

    private boolean isAccepting() {
        return accepting.get();
    }

    private long nextTriggerSequence() {
        return triggerSequence.incrementAndGet();
    }

    private void offerTrigger(SchedulerTrigger trigger) {
        triggers.offer(trigger);
    }

    private void removeTrigger(SchedulerTrigger trigger) {
        triggers.remove(trigger);
    }

    private void removeTriggers(SchedulerSchedule schedule) {
        triggers.removeIf(trigger -> trigger.schedule() == schedule);
    }

    private void removeQueuedRun(SchedulerRun run) {
        executor.remove(run);
    }

    private void removeSchedule(UUID id, SchedulerSchedule schedule) {
        schedules.remove(id, schedule);
    }

    private void onRunExited(SchedulerRun run) {
        activeRuns.remove(run.id(), run);
    }

    private void submitRun(SchedulerRun run, boolean propagateRejection) {
        RejectedExecutionException lifecycleRejection = null;
        synchronized (lifecycleLock) {
            if (!accepting.get()) {
                lifecycleRejection = new RejectedExecutionException("Scheduler is shut down");
            } else if (run.state() != RunState.QUEUED) {
                return;
            } else {
                activeRuns.put(run.id(), run);
            }
        }
        if (lifecycleRejection != null) {
            run.reject(lifecycleRejection);
            if (propagateRejection) {
                throw lifecycleRejection;
            }
            return;
        }
        try {
            executor.execute(run);
        } catch (RejectedExecutionException rejection) {
            run.reject(rejection);
            if (propagateRejection) {
                throw rejection;
            }
        }
    }

    private void fireStart(UUID scheduleId, UUID runId) {
        for (ScheduleEventListener listener : listeners) {
            try {
                listener.onRunStart(scheduleId, runId);
            } catch (Throwable ignored) {
                // Listener failures cannot change scheduler state.
            }
        }
    }

    private void fireOutcome(UUID scheduleId, RunOutcome outcome) {
        for (ScheduleEventListener listener : listeners) {
            try {
                listener.onRunComplete(scheduleId, outcome);
            } catch (Throwable ignored) {
                // Listener failures cannot change scheduler state.
            }
        }
    }

    private void fireRejected(UUID scheduleId, UUID runId, Throwable failure) {
        for (ScheduleEventListener listener : listeners) {
            try {
                listener.onRunRejected(scheduleId, runId, failure);
            } catch (Throwable ignored) {
                // Listener failures cannot change scheduler state.
            }
        }
    }

    private void fireSkipped(UUID scheduleId) {
        for (ScheduleEventListener listener : listeners) {
            try {
                listener.onRunSkipped(scheduleId);
            } catch (Throwable ignored) {
                // Listener failures cannot change scheduler state.
            }
        }
    }

    private void ensureAccepting() {
        if (!accepting.get()) {
            throw new RejectedExecutionException("Scheduler is shut down");
        }
    }

    private void dispatchLoop() {
        List<SchedulerTrigger> dueTriggers = new ArrayList<>(MAX_TRIGGER_DISPATCH_BATCH);
        try {
            while (dispatcherRunning.get()) {
                try {
                    SchedulerTrigger trigger = triggers.take();
                    dispatchTriggerSafely(trigger);
                    triggers.drainTo(dueTriggers, MAX_TRIGGER_DISPATCH_BATCH);
                    for (SchedulerTrigger dueTrigger : dueTriggers) {
                        if (!dispatcherRunning.get()) {
                            break;
                        }
                        dispatchTriggerSafely(dueTrigger);
                    }
                } catch (InterruptedException interrupted) {
                    if (!dispatcherRunning.get()) {
                        break;
                    }
                } finally {
                    dueTriggers.clear();
                }
            }
        } finally {
            dispatcherTerminated.countDown();
        }
    }

    private void dispatchTriggerSafely(SchedulerTrigger trigger) {
        try {
            if (trigger.run() != null) {
                dispatchDelayedRun(trigger.run());
            } else if (trigger.schedule() != null) {
                trigger.schedule().onTrigger(trigger);
            }
        } catch (Throwable ignored) {
            // A malformed schedule must not terminate timing for unrelated schedules.
        }
    }

    private void dispatchDelayedRun(SchedulerRun run) {
        if (!accepting.get()) {
            run.cancelBeforeExecution("Scheduler shutdown before delayed execution");
            return;
        }
        if (!run.prepareDelayedDispatch()) {
            return;
        }
        submitRun(run, false);
    }

    private boolean isCurrentWorkerThread() {
        Thread current = Thread.currentThread();
        for (SchedulerRun run : activeRuns.values()) {
            if (run.isRunningOn(current)) {
                return true;
            }
        }
        return false;
    }

    private void stopDispatcher() {
        if (dispatcherRunning.compareAndSet(true, false)) {
            triggers.clear();
            dispatcher.interrupt();
        }
    }

    private final class RuntimeContext implements SchedulerRuntimeContext {
        @Override
        public Instant now() {
            return UnifiedScheduler.this.now();
        }

        @Override
        public boolean isAccepting() {
            return UnifiedScheduler.this.isAccepting();
        }

        @Override
        public boolean offerScheduleTrigger(
                SchedulerSchedule schedule,
                long generation,
                Instant instant
        ) {
            synchronized (lifecycleLock) {
                if (!accepting.get() || schedule.state() != ScheduleState.ACTIVE) {
                    return false;
                }
                offerTrigger(SchedulerTrigger.forSchedule(
                        schedule,
                        generation,
                        instant,
                        nextTriggerSequence()
                ));
                return true;
            }
        }

        @Override
        public void removeTrigger(SchedulerTrigger trigger) {
            UnifiedScheduler.this.removeTrigger(trigger);
        }

        @Override
        public void removeTriggers(SchedulerSchedule schedule) {
            UnifiedScheduler.this.removeTriggers(schedule);
        }

        @Override
        public void removeQueuedRun(SchedulerRun run) {
            UnifiedScheduler.this.removeQueuedRun(run);
        }

        @Override
        public void removeSchedule(UUID id, SchedulerSchedule schedule) {
            UnifiedScheduler.this.removeSchedule(id, schedule);
        }

        @Override
        public void onRunExited(SchedulerRun run) {
            UnifiedScheduler.this.onRunExited(run);
        }

        @Override
        public void submitRun(SchedulerRun run, boolean propagateRejection) {
            UnifiedScheduler.this.submitRun(run, propagateRejection);
        }

        @Override
        public void fireStart(UUID scheduleId, UUID runId) {
            UnifiedScheduler.this.fireStart(scheduleId, runId);
        }

        @Override
        public void fireOutcome(UUID scheduleId, RunOutcome outcome) {
            UnifiedScheduler.this.fireOutcome(scheduleId, outcome);
        }

        @Override
        public void fireRejected(UUID scheduleId, UUID runId, Throwable failure) {
            UnifiedScheduler.this.fireRejected(scheduleId, runId, failure);
        }

        @Override
        public void fireSkipped(UUID scheduleId) {
            UnifiedScheduler.this.fireSkipped(scheduleId);
        }
    }

    /** Builder for execution, timing, shutdown and listener policies. */
    public static final class Builder {
        private int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int queueCapacity = 10_000;
        private Duration keepAlive = Duration.ofSeconds(60);
        private boolean allowCoreThreadTimeout = true;
        private Clock clock = Clock.systemUTC();
        private ShutdownPolicy shutdownPolicy = ShutdownPolicy.defaults();
        private ThreadPoolExecutor executor;
        private ThreadFactory workerThreadFactory =
                new SchedulerThreadFactory("unified-exec", false);
        private ThreadFactory timerThreadFactory =
                new SchedulerThreadFactory("unified-timer", true);
        private final List<ScheduleEventListener> listeners = new ArrayList<>();

        private Builder() {
        }

        public Builder singleThreaded() {
            parallelism = 1;
            return this;
        }

        public Builder parallelism(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("parallelism must be greater than zero");
            }
            parallelism = value;
            return this;
        }

        public Builder queueCapacity(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("queueCapacity must be greater than zero");
            }
            queueCapacity = value;
            return this;
        }

        public Builder keepAlive(Duration value) {
            keepAlive = DelayedSchedule.requireNonNegative(value, "keepAlive");
            return this;
        }

        public Builder allowCoreThreadTimeout(boolean value) {
            allowCoreThreadTimeout = value;
            return this;
        }

        public Builder threadNamePrefix(String value) {
            workerThreadFactory = new SchedulerThreadFactory(requireName(value), false);
            return this;
        }

        public Builder timerThreadName(String value) {
            timerThreadFactory = new SchedulerThreadFactory(requireName(value), true);
            return this;
        }

        public Builder threadFactory(ThreadFactory value) {
            workerThreadFactory = Objects.requireNonNull(value, "threadFactory");
            return this;
        }

        public Builder timerThreadFactory(ThreadFactory value) {
            timerThreadFactory = Objects.requireNonNull(value, "timerThreadFactory");
            return this;
        }

        public Builder clock(Clock value) {
            clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        public Builder shutdownPolicy(ShutdownPolicy value) {
            shutdownPolicy = Objects.requireNonNull(value, "shutdownPolicy");
            return this;
        }

        public Builder executor(ThreadPoolExecutor value) {
            executor = Objects.requireNonNull(value, "executor");
            return this;
        }

        public Builder addListener(ScheduleEventListener value) {
            listeners.add(Objects.requireNonNull(value, "listener"));
            return this;
        }

        public UnifiedScheduler build() {
            return new UnifiedScheduler(this);
        }

        private static String requireName(String value) {
            Objects.requireNonNull(value, "threadName");
            if (value.isBlank()) {
                throw new IllegalArgumentException("threadName must not be blank");
            }
            return value;
        }
    }
}
