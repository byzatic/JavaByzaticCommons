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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class UnifiedSchedulerTest {

    @Test
    public void serialLanePreservesFifoWithoutOwningAnotherExecutor() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder().parallelism(4).build();
        ExecutionLane lane = scheduler.serialLane("reload");
        try {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            List<CompletionStage<Void>> completions = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                final int value = index;
                completions.add(lane.submit(() -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    order.add(value);
                    active.decrementAndGet();
                }));
            }
            CompletableFuture.allOf(completions.stream()
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
            lane.shutdown();
            assertTrue(lane.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(1, maximumActive.get());
            for (int index = 0; index < 50; index++) assertEquals(index, order.get(index).intValue());
        } finally {
            lane.close();
            scheduler.close();
        }
    }

    @Test
    public void serialLaneReportsTaskFailureAndSupportsForcedShutdown() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder().parallelism(2).build();
        ExecutionLane lane = scheduler.serialLane("cancellable");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            CompletionStage<Void> failed = lane.submit(() -> {
                throw new IllegalStateException("expected");
            });
            try {
                failed.toCompletableFuture().get(1, TimeUnit.SECONDS);
                fail("Lane task failure must be propagated");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }

            lane.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            lane.shutdownNow();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(lane.awaitTermination(Duration.ofSeconds(1)));
            try {
                lane.submit(() -> { });
                fail("Closed lane must reject new work");
            } catch (RejectedExecutionException expected) {
                // expected
            }
        } finally {
            lane.shutdownNow();
            scheduler.close();
        }
    }

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
    public void runAndScheduleIdentifiersRemainRfc4122VersionFourUuids() throws Exception {
        UnifiedScheduler scheduler = UnifiedScheduler.builder().singleThreaded().build();
        try {
            RunHandle run = scheduler.submit(() -> { });
            ScheduleHandle schedule = scheduler.schedule(
                    cancellation -> { },
                    Schedules.after(Duration.ofMillis(10L))
            );

            assertEquals(4, run.id().version());
            assertEquals(2, run.id().variant());
            assertEquals(4, schedule.id().version());
            assertEquals(2, schedule.id().variant());
            assertEquals(RunState.COMPLETED, run.await(Duration.ofSeconds(1L)).state());
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
    public void dueTriggerBatchDoesNotLoseDelayedRuns() throws Exception {
        int taskCount = 2_500;
        UnifiedScheduler scheduler = UnifiedScheduler.builder()
                .parallelism(4)
                .queueCapacity(taskCount)
                .build();
        CountDownLatch completed = new CountDownLatch(taskCount);
        try {
            for (int index = 0; index < taskCount; index++) {
                scheduler.schedule(
                        cancellation -> completed.countDown(),
                        Duration.ofMillis(10L)
                );
            }
            assertTrue(completed.await(5L, TimeUnit.SECONDS));
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

    @Test
    public void cancellingDequeuedRunPublishesScheduleExitOnlyOnce() throws Exception {
        CountDownLatch dequeued = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean pauseFirstTask = new AtomicBoolean(true);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4),
                java.util.concurrent.Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void beforeExecute(Thread thread, Runnable command) {
                if (pauseFirstTask.compareAndSet(true, false)) {
                    dequeued.countDown();
                    try {
                        releaseWorker.await();
                    } catch (InterruptedException interrupted) {
                        thread.interrupt();
                    }
                }
            }
        };
        UnifiedScheduler scheduler = UnifiedScheduler.builder().executor(executor).build();
        try {
            ScheduleHandle schedule = scheduler.schedule(
                    cancellation -> { },
                    Schedules.fixedDelay(Duration.ZERO, Duration.ofDays(1)));
            assertTrue(dequeued.await(1, TimeUnit.SECONDS));
            RunHandle run = awaitActiveRun(schedule);
            assertTrue(run.requestCancellation("test cancellation after dequeue"));
            releaseWorker.countDown();
            Thread.sleep(50L);

            java.lang.reflect.Field triggersField = UnifiedScheduler.class.getDeclaredField("triggers");
            triggersField.setAccessible(true);
            java.util.concurrent.DelayQueue<?> triggerQueue =
                    (java.util.concurrent.DelayQueue<?>) triggersField.get(scheduler);
            assertEquals("Fixed-delay continuation must be registered exactly once", 1, triggerQueue.size());
            schedule.cancel(Duration.ZERO);
        } finally {
            releaseWorker.countDown();
            scheduler.close();
        }
    }

    @Test
    public void registrationAndShutdownAreLinearized() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            UnifiedScheduler scheduler = UnifiedScheduler.builder().singleThreaded().build();
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<RunHandle> accepted =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread submitter = new Thread(() -> {
                try {
                    start.await();
                    accepted.set(scheduler.schedule(cancellation -> { }, Duration.ofDays(1)));
                } catch (RejectedExecutionException expected) {
                    // Shutdown won the lifecycle race.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            submitter.start();
            start.countDown();
            scheduler.shutdown();
            submitter.join();
            RunHandle run = accepted.get();
            if (run != null) {
                assertEquals(RunState.CANCELLED,
                        run.await(Duration.ofSeconds(1)).state());
            }
            assertTrue(scheduler.awaitTermination(Duration.ofSeconds(1)));
        }
    }

    @Test
    public void forcedLaneShutdownCancelsDrainWhoseHandleIsStillBeingPublished() throws Exception {
        UnifiedScheduler delegate = UnifiedScheduler.builder().singleThreaded().build();
        CountDownLatch submitReached = new CountDownLatch(1);
        CountDownLatch returnHandle = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        UnifiedSchedulerInterface delayedReturn = (UnifiedSchedulerInterface)
                java.lang.reflect.Proxy.newProxyInstance(
                        UnifiedSchedulerInterface.class.getClassLoader(),
                        new Class<?>[]{UnifiedSchedulerInterface.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object result = method.invoke(delegate, arguments);
                                if (method.getName().equals("submit")
                                        && method.getParameterTypes()[0] == Runnable.class) {
                                    submitReached.countDown();
                                    returnHandle.await();
                                }
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
        ExecutionLane lane = new SerialExecutionLane(delayedReturn, "publication-race");
        Thread submitter = new Thread(() -> lane.submit(() -> {
            taskStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        try {
            submitter.start();
            assertTrue(submitReached.await(1L, TimeUnit.SECONDS));
            assertTrue(taskStarted.await(1L, TimeUnit.SECONDS));
            lane.shutdownNow();
            returnHandle.countDown();
            submitter.join(1_000L);
            assertTrue(taskInterrupted.await(1L, TimeUnit.SECONDS));
            assertTrue(lane.awaitTermination(Duration.ofSeconds(1L)));
        } finally {
            returnHandle.countDown();
            lane.shutdownNow();
            delegate.close();
        }
    }

    @Test
    public void forcedCancellationCannotPublishTerminalBeforeStartListenerReturns() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStartToReturn = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean startReturned = new AtomicBoolean(false);
        AtomicBoolean terminalBeforeStart = new AtomicBoolean(false);
        UnifiedScheduler scheduler = UnifiedScheduler.builder()
                .singleThreaded()
                .addListener(new ScheduleEventListener() {
                    @Override public void onRunStart(java.util.UUID scheduleId, java.util.UUID runId) {
                        startEntered.countDown();
                        try {
                            allowStartToReturn.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            startReturned.set(true);
                        }
                    }

                    @Override public void onRunComplete(java.util.UUID scheduleId, RunOutcome outcome) {
                        if (!startReturned.get()) terminalBeforeStart.set(true);
                        terminal.countDown();
                    }
                })
                .build();
        try {
            RunHandle run = scheduler.submit(cancellation -> { });
            assertTrue(startEntered.await(1L, TimeUnit.SECONDS));
            Thread canceller = new Thread(() -> {
                try {
                    run.cancel("forced test cancellation", Duration.ZERO);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            canceller.start();
            assertFalse("terminal publication must wait for start publication",
                    terminal.await(50L, TimeUnit.MILLISECONDS));
            allowStartToReturn.countDown();
            canceller.join(1_000L);
            assertTrue(terminal.await(1L, TimeUnit.SECONDS));
            assertFalse(terminalBeforeStart.get());
        } finally {
            allowStartToReturn.countDown();
            scheduler.close();
        }
    }

    private static RunHandle awaitActiveRun(ScheduleHandle schedule) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.nanoTime() < deadline) {
            List<RunHandle> runs = schedule.activeRuns();
            if (!runs.isEmpty()) return runs.get(0);
            Thread.sleep(1L);
        }
        fail("Schedule did not publish its active run");
        throw new AssertionError("unreachable");
    }
}
