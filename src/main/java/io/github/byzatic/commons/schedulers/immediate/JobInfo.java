package io.github.byzatic.commons.schedulers.immediate;

import java.time.Instant;
import java.util.UUID;

/**
 * Task information.
 */
public final class JobInfo {
    public final UUID id;
    public final JobState state;
    public final Instant lastStart;
    public final Instant lastEnd;
    public final String lastError;

    JobInfo(UUID id, JobState state, Instant lastStart, Instant lastEnd, String lastError) {
        this.id = id;
        this.state = state;
        this.lastStart = lastStart;
        this.lastEnd = lastEnd;
        this.lastError = lastError;
    }

    @Override
    public String toString() {
        return "JobInfo{id=" + id + ", state=" + state + ", lastStart=" + lastStart + ", lastEnd=" + lastEnd +
                (lastError != null ? ", lastError='" + lastError + '\'' : "") + '}';
    }
}
