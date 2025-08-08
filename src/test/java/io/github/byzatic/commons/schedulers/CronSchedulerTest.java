package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CronSchedulerTest {
    CronScheduler scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void schedulesAndRunsTaskEverySecond() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        scheduler = new CronScheduler.Builder().build();
        scheduler.addJob("*/1 * * * * *", token -> latch.countDown());
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    void disallowOverlapPreventsConcurrentRuns() throws Exception {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch atLeastOneCompleted = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder().build();
        scheduler.addJob("*/1 * * * * *", token -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try { Thread.sleep(1500); } finally {
                concurrent.decrementAndGet();
                atLeastOneCompleted.countDown();
            }
        }, true);

        assertTrue(atLeastOneCompleted.await(4, TimeUnit.SECONDS));
        assertEquals(1, maxConcurrent.get(), "overlap should be prevented");
    }

    @Test
    void stopJobCancelsGracefully() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelledEvent = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onCancelled(UUID jobId) { cancelledEvent.countDown(); }
                })
                .defaultGrace(Duration.ofMillis(200))
                .build();

        UUID id = scheduler.addJob("*/1 * * * * *", token -> {
            long end = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < end) {
                if (token.isStopRequested()) return; // graceful out
                Thread.sleep(10);
            }
        }, true);

        assertTrue(started.await(3, TimeUnit.SECONDS));
        scheduler.stopJob(id, Duration.ofMillis(200));
        assertTrue(cancelledEvent.await(3, TimeUnit.SECONDS));
        assertEquals(JobState.CANCELLED, scheduler.query(id).get().state);
    }
}