package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImmediateSchedulerTest {

    ImmediateScheduler scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void addTask_runsImmediately_andOnCompleteEventFires() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        scheduler = new ImmediateScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onComplete(UUID jobId) { completed.countDown(); }
                })
                .build();

        UUID id = scheduler.addTask(token -> ran.countDown());

        assertTrue(ran.await(1, TimeUnit.SECONDS), "Task body did not run immediately");
        assertTrue(completed.await(1, TimeUnit.SECONDS), "onComplete did not fire");
        assertEquals(JobState.COMPLETED, scheduler.query(id).get().state);
    }

    @Test
    void stopTask_requestsGracefulCancel_setsCancelled_andOnCancelledFires() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);

        scheduler = new ImmediateScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onCancelled(UUID jobId) { cancelled.countDown(); }
                })
                .defaultGrace(Duration.ofMillis(200))
                .build();

        UUID id = scheduler.addTask(token -> {
            // Busy loop until stopped
            long end = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < end) {
                if (token.isStopRequested()) return; // cooperative exit
                Thread.sleep(5);
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS), "Task did not start");
        scheduler.stopTask(id, Duration.ofMillis(200));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS), "onCancelled not fired");
        assertEquals(JobState.CANCELLED, scheduler.query(id).get().state);
    }

    @Test
    void stopTask_timeout_interrupts_andOnTimeoutFires() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);

        scheduler = new ImmediateScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onTimeout(UUID jobId) { timedOut.countDown(); }
                })
                .defaultGrace(Duration.ofMillis(50))
                .build();

        UUID id = scheduler.addTask(token -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) { /* interrupted by timeout */ }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        scheduler.stopTask(id, Duration.ofMillis(50));
        assertTrue(timedOut.await(2, TimeUnit.SECONDS), "onTimeout not fired");
        assertEquals(JobState.TIMEOUT, scheduler.query(id).get().state);
    }

    @Test
    void listTasks_containsCreatedTask() {
        scheduler = new ImmediateScheduler.Builder().build();
        UUID id = scheduler.addTask(token -> {});
        assertTrue(scheduler.listTasks().stream().anyMatch(i -> i.id.equals(id)));
    }

    @Test
    void removeTask_marksCancelled_whenNotRunning() {
        scheduler = new ImmediateScheduler.Builder().build();
        UUID id = scheduler.addTask(token -> {}); // runs very fast
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        assertTrue(scheduler.removeTask(id));
        // БОЛЬШЕ НЕ ЖДЕМ состояние — запись удалена
        assertTrue(scheduler.query(id).isEmpty(), "Removed task should not be queryable");
    }

    @Test
    void stopThenRemove_allowsQueryingFinalState() throws Exception {
        scheduler = new ImmediateScheduler.Builder().build();
        UUID id = scheduler.addTask(token -> Thread.sleep(10));
        Thread.sleep(20);
        scheduler.stopTask(id, Duration.ofMillis(50));
        assertTrue(scheduler.query(id).isPresent()); // запись есть
        assertTrue(scheduler.removeTask(id));
        assertTrue(scheduler.query(id).isEmpty());   // а теперь уже нет
    }

    @Test
    void concurrency_manyTasks_runThroughThreadPool() throws Exception {
        scheduler = new ImmediateScheduler.Builder().build();
        int n = 10;
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger ran = new AtomicInteger(0);
        for (int i = 0; i < n; i++) {
            scheduler.addTask(token -> {
                ran.incrementAndGet();
                Thread.sleep(50);
                latch.countDown();
            });
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(n, ran.get());
    }
}
