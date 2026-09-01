package io.github.byzatic.commons.schedulers.unified.internal.scheduling;

import io.github.byzatic.commons.schedulers.unified.CronSchedule;
import io.github.byzatic.commons.schedulers.unified.DelayedSchedule;
import io.github.byzatic.commons.schedulers.unified.FailurePolicy;
import io.github.byzatic.commons.schedulers.unified.FixedDelaySchedule;
import io.github.byzatic.commons.schedulers.unified.FixedRateSchedule;
import io.github.byzatic.commons.schedulers.unified.ImmediateSchedule;
import io.github.byzatic.commons.schedulers.unified.MisfirePolicy;
import io.github.byzatic.commons.schedulers.unified.OverlapPolicy;
import io.github.byzatic.commons.schedulers.unified.RunHandle;
import io.github.byzatic.commons.schedulers.unified.RunOutcome;
import io.github.byzatic.commons.schedulers.unified.RunState;
import io.github.byzatic.commons.schedulers.unified.Schedule;
import io.github.byzatic.commons.schedulers.unified.ScheduleHandle;
import io.github.byzatic.commons.schedulers.unified.ScheduleOptions;
import io.github.byzatic.commons.schedulers.unified.ScheduleState;
import io.github.byzatic.commons.schedulers.unified.ScheduledTask;
import io.github.byzatic.commons.schedulers.unified.internal.core.SchedulerRuntimeContext;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SchedulerRun;
import io.github.byzatic.commons.schedulers.unified.internal.support.SchedulerIdentifiers;
import io.github.byzatic.commons.schedulers.unified.internal.timing.SchedulerTrigger;
import org.jetbrains.annotations.ApiStatus;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Internal state machine for one logical recurring or one-shot schedule. */
@ApiStatus.Internal
public final class SchedulerSchedule implements ScheduleHandle {
    private final SchedulerRuntimeContext scheduler;
    private final UUID id = SchedulerIdentifiers.newIdentifier();
    private final ScheduledTask task;
    private final Schedule schedule;
    private final ScheduleOptions options;
    private final AtomicReference<ScheduleState> state =
            new AtomicReference<>(ScheduleState.ACTIVE);
    private final ConcurrentMap<UUID, SchedulerRun> runs = new ConcurrentHashMap<>();
    private final AtomicBoolean coalesced = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final Object transitionLock = new Object();

    private volatile Instant nextExecution;
    private volatile RunOutcome lastOutcome;

    public SchedulerSchedule(
            SchedulerRuntimeContext scheduler,
            ScheduledTask task,
            Schedule schedule,
            ScheduleOptions options
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.task = Objects.requireNonNull(task, "task");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public Schedule schedule() {
        return schedule;
    }

    @Override
    public ScheduleState state() {
        return state.get();
    }

    @Override
    public Optional<Instant> nextExecutionTime() {
        return Optional.ofNullable(nextExecution);
    }

    @Override
    public Optional<RunOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    @Override
    public List<RunHandle> activeRuns() {
        return Collections.unmodifiableList(new ArrayList<RunHandle>(runs.values()));
    }

    public void scheduleInitial() {
        Instant now = scheduler.now();
        Instant first;
        if (schedule instanceof ImmediateSchedule) {
            first = now;
        } else if (schedule instanceof DelayedSchedule) {
            first = now.plus(((DelayedSchedule) schedule).delay());
        } else if (schedule instanceof FixedDelaySchedule) {
            first = now.plus(((FixedDelaySchedule) schedule).initialDelay());
        } else if (schedule instanceof FixedRateSchedule) {
            first = now.plus(((FixedRateSchedule) schedule).initialDelay());
        } else if (schedule instanceof CronSchedule) {
            CronSchedule cron = (CronSchedule) schedule;
            first = cron.runImmediately()
                    ? now
                    : cron.expression().next(now, cron.zone()).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "Cron has no future fire time: " + cron.expression()
                            )
                    );
        } else {
            throw new IllegalArgumentException(
                    "Unsupported schedule type: " + schedule.getClass().getName()
            );
        }
        scheduleAt(first);
    }

    public void markRegistrationFailed() {
        state.set(ScheduleState.FAILED);
    }

    public void onTrigger(SchedulerTrigger trigger) {
        SchedulerRun run = null;
        boolean skipped = false;
        synchronized (transitionLock) {
            if (state.get() != ScheduleState.ACTIVE
                    || !scheduler.isAccepting()
                    || trigger.generation() != generation.get()) {
                return;
            }
            nextExecution = null;
            boolean occupied = !runs.isEmpty();
            if (occupied && options.overlapPolicy() == OverlapPolicy.SKIP) {
                skipped = true;
            } else if (occupied && options.overlapPolicy() == OverlapPolicy.COALESCE) {
                coalesced.set(true);
            } else {
                run = new SchedulerRun(scheduler, this, task, RunState.QUEUED);
                runs.put(run.id(), run);
            }
        }
        if (skipped) {
            scheduler.fireSkipped(id);
            scheduleNext(trigger.scheduledAt());
            return;
        }
        if (run == null) {
            scheduleNext(trigger.scheduledAt());
            return;
        }
        scheduler.submitRun(run, false);
        scheduleNext(trigger.scheduledAt());
    }

    public void onOutcome(RunOutcome outcome) {
        synchronized (transitionLock) {
            lastOutcome = outcome;
        }
        if (outcome.state() == RunState.FAILED) {
            if (options.failurePolicy() == FailurePolicy.PAUSE_SCHEDULE) {
                pause();
            } else if (options.failurePolicy() == FailurePolicy.CANCEL_SCHEDULE) {
                cancel();
            }
        }
    }

    public void onRunExited(SchedulerRun run) {
        synchronized (transitionLock) {
            runs.remove(run.id(), run);
            if (state.get() != ScheduleState.ACTIVE) {
                return;
            }
            if (schedule instanceof ImmediateSchedule || schedule instanceof DelayedSchedule) {
                state.compareAndSet(ScheduleState.ACTIVE, ScheduleState.COMPLETED);
                scheduler.removeSchedule(id, this);
            } else if (schedule instanceof FixedDelaySchedule) {
                scheduleAt(scheduler.now().plus(((FixedDelaySchedule) schedule).delay()));
            } else if (coalesced.compareAndSet(true, false)) {
                scheduleAt(scheduler.now());
            }
        }
    }

    @Override
    public boolean pause() {
        synchronized (transitionLock) {
            if (!state.compareAndSet(ScheduleState.ACTIVE, ScheduleState.PAUSED)) {
                return false;
            }
            generation.incrementAndGet();
            removePendingTriggers();
            nextExecution = null;
            return true;
        }
    }

    @Override
    public boolean resume() {
        synchronized (transitionLock) {
            if (!scheduler.isAccepting()
                    || !state.compareAndSet(ScheduleState.PAUSED, ScheduleState.ACTIVE)) {
                return false;
            }
            generation.incrementAndGet();
            scheduleInitial();
            return true;
        }
    }

    @Override
    public boolean cancel() {
        try {
            return cancel(options.cancellationGrace());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public boolean cancel(Duration grace) throws InterruptedException {
        List<SchedulerRun> runsToStop;
        synchronized (transitionLock) {
            ScheduleState previous = state.getAndSet(ScheduleState.CANCELLED);
            if (previous == ScheduleState.CANCELLED || previous == ScheduleState.COMPLETED) {
                return false;
            }
            generation.incrementAndGet();
            removePendingTriggers();
            nextExecution = null;
            scheduler.removeSchedule(id, this);
            runsToStop = new ArrayList<>(runs.values());
        }
        stopRuns(runsToStop, grace);
        return true;
    }

    @Override
    public boolean stopActiveRuns(Duration grace) throws InterruptedException {
        List<SchedulerRun> runsToStop;
        synchronized (transitionLock) {
            runsToStop = new ArrayList<>(runs.values());
        }
        return stopRuns(runsToStop, grace);
    }

    public void cancelFutureTriggers() {
        synchronized (transitionLock) {
            ScheduleState current = state.get();
            if (current == ScheduleState.ACTIVE || current == ScheduleState.PAUSED) {
                state.set(ScheduleState.CANCELLED);
            }
            generation.incrementAndGet();
            removePendingTriggers();
            nextExecution = null;
            scheduler.removeSchedule(id, this);
        }
    }

    private void scheduleAt(Instant instant) {
        synchronized (transitionLock) {
            if (state.get() != ScheduleState.ACTIVE || !scheduler.isAccepting()) {
                return;
            }
            if (scheduler.offerScheduleTrigger(this, generation.get(), instant)) {
                nextExecution = instant;
            }
        }
    }

    private void scheduleNext(Instant previousScheduledAt) {
        if (state.get() != ScheduleState.ACTIVE) {
            return;
        }
        Instant now = scheduler.now();
        if (schedule instanceof FixedDelaySchedule) {
            return;
        }
        if (schedule instanceof FixedRateSchedule) {
            FixedRateSchedule fixedRate = (FixedRateSchedule) schedule;
            Instant next = previousScheduledAt.plus(fixedRate.period());
            if (next.isBefore(now)) {
                next = options.misfirePolicy() == MisfirePolicy.FIRE_ONCE_NOW
                        ? now
                        : now.plus(fixedRate.period());
            }
            scheduleAt(next);
        } else if (schedule instanceof CronSchedule) {
            CronSchedule cron = (CronSchedule) schedule;
            Instant base = previousScheduledAt.isBefore(now)
                    && options.misfirePolicy() == MisfirePolicy.SKIP
                    ? now
                    : previousScheduledAt;
            Instant next = cron.expression().next(base, cron.zone()).orElse(null);
            if (next != null) {
                scheduleAt(next);
            }
        }
    }

    private boolean stopRuns(List<SchedulerRun> runsToStop, Duration grace)
            throws InterruptedException {
        boolean changed = false;
        for (SchedulerRun run : runsToStop) {
            changed |= run.cancel("Schedule stop requested", grace);
        }
        return changed;
    }

    private void removePendingTriggers() {
        scheduler.removeTriggers(this);
    }
}
