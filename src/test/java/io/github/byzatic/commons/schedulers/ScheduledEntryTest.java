package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledEntryTest {

    @Test
    void compareOrdersByTriggerTime() {
        long now = System.currentTimeMillis();
        ScheduledEntry early = new ScheduledEntry(UUID.randomUUID(), now + 1000);
        ScheduledEntry late  = new ScheduledEntry(UUID.randomUUID(), now + 2000);
        assertTrue(early.compareTo(late) < 0);
        assertTrue(late.compareTo(early) > 0);
    }

    @Test
    void delayDecreasesOverTime() throws Exception {
        long now = System.currentTimeMillis();
        ScheduledEntry e = new ScheduledEntry(UUID.randomUUID(), now + 200);
        long d1 = e.getDelay(TimeUnit.MILLISECONDS);
        Thread.sleep(120);
        long d2 = e.getDelay(TimeUnit.MILLISECONDS);
        assertTrue(d2 < d1);
    }
}