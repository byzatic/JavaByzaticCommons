package io.github.byzatic.commons.schedulers.unified.internal.timing;

import io.github.byzatic.commons.schedulers.unified.internal.execution.SchedulerRun;
import io.github.byzatic.commons.schedulers.unified.internal.scheduling.SchedulerSchedule;
import org.jetbrains.annotations.ApiStatus;

import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

@ApiStatus.Internal
public final class SchedulerTrigger implements Delayed {
    private final SchedulerRun run;
    private final SchedulerSchedule schedule;
    private final long generation;
    private final Instant scheduledAt;

    private final long triggerAtMillis;
    private final long sequence;

    private SchedulerTrigger(
            SchedulerRun run,
            SchedulerSchedule schedule,
            long generation,
            Instant scheduledAt,
            long sequence
    ) {
        this.run = run;
        this.schedule = schedule;
        this.generation = generation;
        this.scheduledAt = scheduledAt;
        this.triggerAtMillis = scheduledAt.toEpochMilli();
        this.sequence = sequence;
    }

    public static SchedulerTrigger forRun(SchedulerRun run, Instant at, long sequence) {
        return new SchedulerTrigger(run, null, 0L, at, sequence);
    }

    public static SchedulerTrigger forSchedule(
            SchedulerSchedule schedule,
            long generation,
            Instant at,
            long sequence
    ) {
        return new SchedulerTrigger(null, schedule, generation, at, sequence);
    }

    public SchedulerRun run() {
        return run;
    }

    public SchedulerSchedule schedule() {
        return schedule;
    }

    public long generation() {
        return generation;
    }

    public Instant scheduledAt() {
        return scheduledAt;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(triggerAtMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        SchedulerTrigger that = (SchedulerTrigger) other;
        int byTime = Long.compare(triggerAtMillis, that.triggerAtMillis);
        return byTime != 0 ? byTime : Long.compare(sequence, that.sequence);
    }
}
