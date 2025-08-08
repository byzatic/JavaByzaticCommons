package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JobInfoTest {
    @Test
    void holdsFields() {
        UUID id = UUID.randomUUID();
        JobInfo info = new JobInfo(id, "cron", JobState.SCHEDULED,
                Instant.parse("2025-08-08T11:00:00Z"), null, null);
        assertEquals(id, info.id);
        assertEquals("cron", info.cron);
        assertEquals(JobState.SCHEDULED, info.state);
        assertEquals(Instant.parse("2025-08-08T11:00:00Z"), info.lastStart);
        assertNull(info.lastEnd);
        assertNull(info.lastError);
        assertTrue(info.toString().contains("JobInfo"));
    }
}