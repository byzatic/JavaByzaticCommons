package io.github.byzatic.commons.schedulers.cron;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobStateTest {
    @Test
    void enumContainsExpectedValues() {
        assertNotNull(JobState.valueOf("SCHEDULED"));
        assertNotNull(JobState.valueOf("RUNNING"));
        assertNotNull(JobState.valueOf("COMPLETED"));
        assertNotNull(JobState.valueOf("FAILED"));
        assertNotNull(JobState.valueOf("CANCELLED"));
        assertNotNull(JobState.valueOf("TIMEOUT"));
    }
}