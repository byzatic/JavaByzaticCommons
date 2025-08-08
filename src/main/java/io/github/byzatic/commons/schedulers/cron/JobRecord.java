package io.github.byzatic.commons.schedulers.cron;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class JobRecord {
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
