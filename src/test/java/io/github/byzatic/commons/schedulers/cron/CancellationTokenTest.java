package io.github.byzatic.commons.schedulers.cron;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTokenTest {

    @Test
    void requestStopSetsFlagAndReason() {
        CancellationToken t = new CancellationToken();
        assertFalse(t.isStopRequested());
        t.requestStop("because");
        assertTrue(t.isStopRequested());
        assertEquals("because", t.reason());
    }

    @Test
    void throwIfStopRequestedThrows() {
        CancellationToken t = new CancellationToken();
        t.requestStop("halt");
        InterruptedException ex = assertThrows(InterruptedException.class, t::throwIfStopRequested);
        assertTrue(ex.getMessage().contains("halt"));
    }
}