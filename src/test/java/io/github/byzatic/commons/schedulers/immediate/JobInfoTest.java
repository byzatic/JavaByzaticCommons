package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobInfoTest {

    @Test
    void holdsFieldsAndToStringContainsClassName() {
        UUID id = UUID.randomUUID();
        JobInfo info =
                new JobInfo(id, JobState.SCHEDULED,
                        Instant.parse("2025-08-08T11:00:00Z"), null, null);

        assertEquals(id, info.id);
        assertEquals(JobState.SCHEDULED, info.state);
        assertEquals(Instant.parse("2025-08-08T11:00:00Z"), info.lastStart);
        assertNull(info.lastEnd);
        assertNull(info.lastError);
        assertTrue(info.toString().contains("JobInfo"));
    }
}
