package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTokenTest {

    @Test
    void defaultNotStopped_thenRequestStop_setsFlagAndReason() {
        CancellationToken t = new CancellationToken();
        assertFalse(t.isStopRequested());
        assertEquals("", t.reason());

        t.requestStop("because");
        assertTrue(t.isStopRequested());
        assertEquals("because", t.reason());
    }

    @Test
    void throwIfStopRequested_throwsInterruptedException() {
        CancellationToken t = new CancellationToken();
        t.requestStop("halt");
        InterruptedException ex = assertThrows(InterruptedException.class, t::throwIfStopRequested);
        assertTrue(ex.getMessage().contains("halt"));
    }
}