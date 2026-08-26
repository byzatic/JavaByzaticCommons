package io.github.byzatic.commons.schedulers.unified;

import io.github.byzatic.commons.schedulers.cron.CronScheduler;
import io.github.byzatic.commons.schedulers.immediate.ImmediateScheduler;
import org.junit.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LegacySchedulerFacadeTest {

    @Test
    public void immediateFacadeHandlesCompletionRacingWithRegistration() throws Exception {
        int taskCount = 25_000;
        CountDownLatch completed = new CountDownLatch(taskCount);
        Set<UUID> started = ConcurrentHashMap.newKeySet();
        AtomicInteger terminalBeforeStart = new AtomicInteger();
        UnifiedScheduler unified = UnifiedScheduler.builder()
                .parallelism(8)
                .queueCapacity(taskCount)
                .build();
        ImmediateScheduler scheduler = ImmediateScheduler.adapt(unified);
        scheduler.addListener(new io.github.byzatic.commons.schedulers.immediate.JobEventListener() {
            @Override public void onStart(UUID jobId) { started.add(jobId); }
            @Override public void onComplete(UUID jobId) {
                if (!started.contains(jobId)) terminalBeforeStart.incrementAndGet();
                completed.countDown();
            }
        });
        try {
            for (int index = 0; index < taskCount; index++) {
                scheduler.addTask(cancellation -> { });
            }
            assertTrue(completed.await(5L, TimeUnit.SECONDS));
            assertEquals(0, terminalBeforeStart.get());
        } finally {
            scheduler.close();
            unified.close();
        }
    }

    @Test
    public void immediateFacadeRetainsLegacyQueryContract() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        ImmediateScheduler scheduler = new ImmediateScheduler.Builder()
                .addListener(new io.github.byzatic.commons.schedulers.immediate.JobEventListener() {
                    @Override public void onComplete(UUID jobId) { completed.countDown(); }
                })
                .build();
        try {
            UUID id = scheduler.addTask(cancellation -> { });
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(io.github.byzatic.commons.schedulers.immediate.JobState.COMPLETED,
                    scheduler.query(id).get().state);
            assertTrue(scheduler.removeTask(id));
            assertFalse(scheduler.query(id).isPresent());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void cronFacadeSupportsImmediateStartAndCooperativeStop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CronScheduler scheduler = new CronScheduler.Builder()
                .addListener(new io.github.byzatic.commons.schedulers.cron.JobEventListener() {
                    @Override public void onCancelled(UUID jobId) { cancelled.countDown(); }
                })
                .build();
        try {
            UUID id = scheduler.addJob("0 0 1 1 * *", cancellation -> {
                started.countDown();
                while (!cancellation.isStopRequested()) Thread.sleep(5L);
            }, true, true);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            scheduler.stopJob(id, Duration.ofSeconds(1));
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertEquals(io.github.byzatic.commons.schedulers.cron.JobState.CANCELLED,
                    scheduler.query(id).get().state);
            assertTrue(scheduler.removeJob(id));
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void borrowedFacadesDoNotCloseUnifiedScheduler() throws Exception {
        UnifiedScheduler unified = UnifiedScheduler.builder().parallelism(2).build();
        ImmediateScheduler immediate = ImmediateScheduler.adapt(unified);
        CronScheduler cron = CronScheduler.adapt(
                unified, java.time.ZoneId.of("UTC"), Duration.ofSeconds(1));
        try {
            UUID immediateId = immediate.addTask(cancellation -> { });
            while (immediate.query(immediateId).get().state
                    != io.github.byzatic.commons.schedulers.immediate.JobState.COMPLETED) {
                Thread.sleep(2L);
            }
            immediate.close();
            cron.close();
            assertFalse(unified.isShutdown());
            assertEquals(RunState.COMPLETED,
                    unified.submit(cancellation -> { }).await(Duration.ofSeconds(1)).state());
        } finally {
            immediate.close();
            cron.close();
            unified.close();
        }
    }
}
