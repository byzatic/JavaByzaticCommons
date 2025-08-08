package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobEventListenerTest {
    CronScheduler scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void receivesStartAndComplete() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onComplete(UUID jobId) { completed.countDown(); }
                    @Override public void onError(UUID jobId, Throwable error) { errors.incrementAndGet(); }
                })
                .build();

        scheduler.addJob("*/1 * * * * *", token -> {});
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
    }
}