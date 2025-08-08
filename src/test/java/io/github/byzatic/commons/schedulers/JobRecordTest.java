package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobRecordTest {

    @Test
    void defaultFieldsAreCorrect() {
        CronExpr expr = CronExpr.parse("*/1 * * * * *");
        CronTask task = token -> {};
        JobRecord rec = new JobRecord(UUID.randomUUID(), expr, task, ZoneId.of("UTC"), true);
        assertEquals(JobState.SCHEDULED, rec.state);
        assertNull(rec.lastStart);
        assertNull(rec.lastEnd);
        assertNull(rec.lastError);
        assertTrue(rec.disallowOverlap);
        assertNull(rec.runningFuture);
        assertNull(rec.tokenRef.get());
        assertFalse(rec.removed.get());
        assertFalse(rec.isRunning.get());
    }
}
