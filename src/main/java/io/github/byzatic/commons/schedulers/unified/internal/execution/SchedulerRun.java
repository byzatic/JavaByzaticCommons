package io.github.byzatic.commons.schedulers.unified.internal.execution;

import io.github.byzatic.commons.schedulers.unified.CancellationContext;
import io.github.byzatic.commons.schedulers.unified.RunHandle;
import io.github.byzatic.commons.schedulers.unified.RunOutcome;
import io.github.byzatic.commons.schedulers.unified.RunState;
import io.github.byzatic.commons.schedulers.unified.ScheduledTask;
import io.github.byzatic.commons.schedulers.unified.internal.core.SchedulerRuntimeContext;
import io.github.byzatic.commons.schedulers.unified.internal.scheduling.SchedulerSchedule;
import io.github.byzatic.commons.schedulers.unified.internal.support.SchedulerIdentifiers;
import io.github.byzatic.commons.schedulers.unified.internal.timing.SchedulerTrigger;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Internal state machine for one physical task execution. */
@ApiStatus.Internal
public final class SchedulerRun implements RunHandle, Runnable {
    private final SchedulerRuntimeContext scheduler;
    private final UUID id = SchedulerIdentifiers.newIdentifier();
    private final SchedulerSchedule owner;
    private final ScheduledTask task;
    private final CancellationContext cancellation = new CancellationContext();
    private final AtomicReference<RunState> state;
    private final AtomicBoolean cancellationCallbackSent = new AtomicBoolean(false);
    private final AtomicBoolean outcomePublished = new AtomicBoolean(false);
    private final AtomicBoolean executionExitPublished = new AtomicBoolean(false);
    private final CompletableFuture<Void> startPublished = new CompletableFuture<>();
    private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();

    private volatile Thread runner;
    private volatile SchedulerTrigger pendingTrigger;
    private volatile Instant startedAt;

    public SchedulerRun(
            SchedulerRuntimeContext scheduler,
            SchedulerSchedule owner,
            ScheduledTask task,
            RunState initialState
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.owner = owner;
        this.task = Objects.requireNonNull(task, "task");
        this.state = new AtomicReference<>(Objects.requireNonNull(initialState, "initialState"));
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public Optional<UUID> scheduleId() {
        return owner == null ? Optional.empty() : Optional.of(owner.id());
    }

    @Override
    public RunState state() {
        return state.get();
    }

    @Override
    public void run() {
        runner = Thread.currentThread();
        if (!state.compareAndSet(RunState.QUEUED, RunState.RUNNING)) {
            runner = null;
            actualExecutionFinished();
            return;
        }
        try {
            startedAt = scheduler.now();
            try {
                scheduler.fireStart(scheduleIdentifier(), id);
            } finally {
                startPublished.complete(null);
            }
            if (state.get() != RunState.RUNNING) {
                publishExistingState(null);
                return;
            }
            task.run(cancellation);
            publish(cancellation.isCancellationRequested()
                    ? RunState.CANCELLED
                    : RunState.COMPLETED, null);
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
        if (isTerminal(current)) {
            return false;
        }
        cancellation.requestCancellation(reason);
        notifyCancellationCallback();
        if (state.compareAndSet(RunState.WAITING, RunState.CANCELLED)
                || state.compareAndSet(RunState.QUEUED, RunState.CANCELLED)) {
            SchedulerTrigger trigger = pendingTrigger;
            if (trigger != null) {
                scheduler.removeTrigger(trigger);
            }
            pendingTrigger = null;
            scheduler.removeQueuedRun(this);
            publishExistingState(null);
            actualExecutionFinished();
        }
        return true;
    }

    @Override
    public boolean cancel(String reason, Duration grace) throws InterruptedException {
        Duration validGrace = requireNonNegative(grace, "grace");
        if (!requestCancellation(reason)) {
            return false;
        }
        if (state.get() == RunState.RUNNING) {
            if (runner == Thread.currentThread()) {
                return true;
            }
            try {
                completion.get(validGrace.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                forceCancellation(reason, true);
            } catch (ExecutionException impossible) {
                // Completion always contains RunOutcome.
            }
        }
        return true;
    }

    public void forceCancellation(String reason, boolean timedOut) {
        requestCancellation(reason);
        RunState terminal = timedOut ? RunState.TIMED_OUT : RunState.CANCELLED;
        if (state.compareAndSet(RunState.RUNNING, terminal)) {
            if (runner == Thread.currentThread() && !startPublished.isDone()) {
                return;
            }
            startPublished.join();
            publishExistingState(null);
            Thread currentRunner = runner;
            if (currentRunner != null) {
                currentRunner.interrupt();
            }
        }
    }

    public void cancelBeforeExecution(String reason) {
        requestCancellation(reason);
    }

    public void reject(Throwable failure) {
        RunState current = state.get();
        if ((current == RunState.QUEUED || current == RunState.WAITING)
                && state.compareAndSet(current, RunState.REJECTED)) {
            publishExistingState(failure);
            scheduler.fireRejected(scheduleIdentifier(), id, failure);
            actualExecutionFinished();
        }
    }

    public boolean prepareDelayedDispatch() {
        pendingTrigger = null;
        return state.compareAndSet(RunState.WAITING, RunState.QUEUED);
    }

    public void pendingTrigger(SchedulerTrigger trigger) {
        pendingTrigger = trigger;
    }

    public boolean isRunningOn(Thread thread) {
        return runner == thread;
    }

    @Override
    public RunOutcome await() throws InterruptedException, ExecutionException {
        return throwIfFailed(completion.get());
    }

    @Override
    public RunOutcome await(Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Duration valid = requireNonNegative(timeout, "timeout");
        return throwIfFailed(completion.get(valid.toNanos(), TimeUnit.NANOSECONDS));
    }

    @Override
    public CompletionStage<RunOutcome> completion() {
        return completion;
    }

    private UUID scheduleIdentifier() {
        return owner == null ? null : owner.id();
    }

    private void notifyCancellationCallback() {
        if (cancellationCallbackSent.compareAndSet(false, true)) {
            try {
                task.onCancellationRequested();
            } catch (Throwable ignored) {
                // Cancellation callbacks are advisory.
            }
        }
    }

    private void publish(RunState terminalState, Throwable failure) {
        if (state.compareAndSet(RunState.RUNNING, terminalState)) {
            publishExistingState(failure);
        }
    }

    private void publishExistingState(Throwable failure) {
        if (!outcomePublished.compareAndSet(false, true)) {
            return;
        }
        RunOutcome outcome = new RunOutcome(id, state.get(), startedAt, scheduler.now(), failure);
        completion.complete(outcome);
        if (owner != null) {
            owner.onOutcome(outcome);
        }
        scheduler.fireOutcome(scheduleIdentifier(), outcome);
    }

    private void actualExecutionFinished() {
        if (!executionExitPublished.compareAndSet(false, true)) {
            return;
        }
        scheduler.onRunExited(this);
        if (owner != null) {
            owner.onRunExited(this);
        }
    }

    private RunOutcome throwIfFailed(RunOutcome outcome) throws ExecutionException {
        if (outcome.state() == RunState.FAILED || outcome.state() == RunState.REJECTED) {
            throw new ExecutionException(outcome.failure().orElse(null));
        }
        return outcome;
    }

    private static boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED
                || state == RunState.FAILED
                || state == RunState.CANCELLED
                || state == RunState.TIMED_OUT
                || state == RunState.REJECTED
                || state == RunState.SKIPPED;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
