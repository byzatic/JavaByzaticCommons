package io.github.byzatic.commons.schedulers.cron;

import java.util.UUID;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

final class ScheduledEntry implements Delayed {
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
