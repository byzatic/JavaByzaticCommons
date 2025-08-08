package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobStateTest {

    @Test
    void containsExpectedValues() {
        assertNotNull(JobState.valueOf("SCHEDULED"));
        assertNotNull(JobState.valueOf("RUNNING"));
        assertNotNull(JobState.valueOf("COMPLETED"));
        assertNotNull(JobState.valueOf("FAILED"));
        assertNotNull(JobState.valueOf("CANCELLED"));
        assertNotNull(JobState.valueOf("TIMEOUT"));
    }
}
