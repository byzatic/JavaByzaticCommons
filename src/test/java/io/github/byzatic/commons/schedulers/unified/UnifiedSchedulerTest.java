package io.github.byzatic.commons.schedulers.unified;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class UnifiedSchedulerTest {

    @Test
    public void singleThreadModeIsSequentialAndOrdered() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder()
                .singleThreaded()
                .threadNamePrefix("serial-owner")
                .build();
        try {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            List<RunHandle> handles = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                final int value = index;
                handles.add(scheduler.submit(cancellation -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    assertTrue(Thread.currentThread().getName().startsWith("serial-owner"));
                    order.add(value);
                    active.decrementAndGet();
                }));
            }
            for (RunHandle handle : handles) assertEquals(RunState.COMPLETED, handle.await().state());
            assertEquals(1, maximumActive.get());
            for (int index = 0; index < 20; index++) assertEquals(index, order.get(index).intValue());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void delayedAndFixedDelayTasksUseTheSameExecutor() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder().singleThreaded().build();
        try {
            long started = System.nanoTime();
            RunHandle delayed = scheduler.schedule(cancellation -> { }, Duration.ofMillis(100));
            assertEquals(RunState.COMPLETED, delayed.await(Duration.ofSeconds(2)).state());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 70L);

            CountDownLatch repeated = new CountDownLatch(3);
            ScheduleHandle schedule = scheduler.schedule(
                    cancellation -> repeated.countDown(),
                    Schedules.fixedDelay(Duration.ZERO, Duration.ofMillis(20)),
                    ScheduleOptions.builder().overlapPolicy(OverlapPolicy.SKIP).build());
            assertTrue(repeated.await(2, TimeUnit.SECONDS));
            assertTrue(schedule.cancel(Duration.ofSeconds(1)));
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void overlapSkipPreventsConcurrentRuns() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        UnifiedScheduler scheduler = UnifiedScheduler.builder()
                .parallelism(4)
                .addListener(new ScheduleEventListener() {
                    @Override public void onRunSkipped(java.util.UUID scheduleId) { skipped.incrementAndGet(); }
                })
                .build();
        try {
            ScheduleHandle handle = scheduler.schedule(cancellation -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                Thread.sleep(80L);
                active.decrementAndGet();
            }, Schedules.fixedRate(Duration.ZERO, Duration.ofMillis(10)),
                    ScheduleOptions.builder().overlapPolicy(OverlapPolicy.SKIP).build());
            Thread.sleep(250L);
            handle.cancel(Duration.ofSeconds(1));
            assertEquals(1, maximum.get());
            assertTrue(skipped.get() > 0);
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void rejectionIsExplicitAndNeverRunsOnCaller() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        UnifiedScheduler scheduler = UnifiedScheduler.builder().executor(executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            RunHandle running = scheduler.submit(cancellation -> release.await());
            scheduler.submit(cancellation -> release.await());
            try {
                scheduler.submit(cancellation -> fail("Rejected task executed on submitting thread"));
                fail("Expected RejectedExecutionException");
            } catch (RejectedExecutionException expected) {
                // expected
            }
            release.countDown();
            assertEquals(RunState.COMPLETED, running.await(Duration.ofSeconds(2)).state());
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    @Test
    public void completedOneShotRunsDoNotCreateScheduleMetadata() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder().parallelism(4).build();
        try {
            List<RunHandle> handles = new ArrayList<>();
            for (int index = 0; index < 500; index++) handles.add(scheduler.submit(cancellation -> { }));
            for (RunHandle handle : handles) handle.await(Duration.ofSeconds(2));
            assertTrue(scheduler.listSchedules().isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void cronExpressionAndImmediateCronSchedulingWork() throws Exception {
        CronExpression expression = CronExpression.parse("*/1 * * * * *");
        assertEquals(Instant.parse("2026-08-26T10:00:01Z"),
                expression.next(Instant.parse("2026-08-26T10:00:00Z"), ZoneId.of("UTC")).get());

        UnifiedScheduler scheduler = UnifiedScheduler.builder().singleThreaded().build();
        try {
            CountDownLatch firstRun = new CountDownLatch(1);
            ScheduleHandle handle = scheduler.schedule(
                    cancellation -> firstRun.countDown(),
                    Schedules.cron("0 0 1 1 * *", ZoneId.of("UTC"), true));
            assertTrue(firstRun.await(1, TimeUnit.SECONDS));
            handle.cancel(Duration.ofSeconds(1));
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void closeFromWorkerDoesNotWaitForItsOwnTermination() throws Exception {
        java.util.concurrent.atomic.AtomicReference<UnifiedScheduler> reference =
                new java.util.concurrent.atomic.AtomicReference<>();
        UnifiedScheduler scheduler = UnifiedScheduler.builder().singleThreaded().build();
        reference.set(scheduler);
        CountDownLatch returnedFromClose = new CountDownLatch(1);
        RunHandle run = scheduler.submit(cancellation -> {
            reference.get().close();
            returnedFromClose.countDown();
        });
        assertTrue(returnedFromClose.await(1, TimeUnit.SECONDS));
        assertEquals(RunState.CANCELLED, run.await(Duration.ofSeconds(1)).state());
        assertTrue(scheduler.awaitTermination(Duration.ofSeconds(1)));
    }
}
