package io.github.byzatic.commons.schedulers.immediate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class JobRecord {
    final UUID id;
    final Task task;
    volatile JobState state = JobState.SCHEDULED;
    volatile Instant lastStart = null;
    volatile Instant lastEnd = null;
    volatile String lastError = null;
    volatile Future<?> runningFuture = null;
    final AtomicReference<CancellationToken> tokenRef = new AtomicReference<>();
    final AtomicBoolean removed = new AtomicBoolean(false);

    JobRecord(UUID id, Task task) {
        this.id = id;
        this.task = task;
    }
}
