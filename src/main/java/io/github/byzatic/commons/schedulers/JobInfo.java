package io.github.byzatic.commons.schedulers;

import java.time.Instant;
import java.util.UUID;

/**
 * Информация о задаче (read-only).
 */
public final class JobInfo {
    public final UUID id;
    public final String cron;
    public final JobState state;
    public final Instant lastStart;
    public final Instant lastEnd;
    public final String lastError;

    JobInfo(UUID id, String cron, JobState state, Instant lastStart, Instant lastEnd, String lastError) {
        this.id = id;
        this.cron = cron;
        this.state = state;
        this.lastStart = lastStart;
        this.lastEnd = lastEnd;
        this.lastError = lastError;
    }

    @Override
    public String toString() {
        return "JobInfo{id=" + id + ", cron='" + cron + "', state=" + state +
                ", lastStart=" + lastStart + ", lastEnd=" + lastEnd +
                (lastError != null ? ", lastError='" + lastError + '\'' : "") + '}';
    }
}
