package io.github.byzatic.commons.schedulers.cron;

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
            try {
                Thread.sleep(1500);
            } finally {
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
                    @Override
                    public void onStart(UUID jobId) {
                        started.countDown();
                    }

                    @Override
                    public void onCancelled(UUID jobId) {
                        cancelledEvent.countDown();
                    }
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

    @Test
    void runImmediately_true_startsRightAway_evenIfCronIsFar() throws Exception {
        // берём крон, который явно не должен сработать скоро (НГ в полночь воскресенье)
        String veryRareCron = "0 0 0 1 1 0";

        CountDownLatch started = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder().build();
        scheduler.addListener(new JobEventListener() {
            @Override
            public void onStart(UUID jobId) {
                started.countDown();
            }
        });

        // ВАЖНО: runImmediately = true
        scheduler.addJob(veryRareCron, token -> {
        }, /*disallowOverlap*/ true, /*runImmediately*/ true);

        // Должно стартовать почти сразу
        assertTrue(started.await(1, TimeUnit.SECONDS), "Первый запуск не стартовал сразу при runImmediately=true");
    }

    @Test
    void runImmediately_false_doesNotStartRightAway_whenCronIsFar() throws Exception {
        String veryRareCron = "0 0 0 1 1 0";

        CountDownLatch started = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder().build();
        scheduler.addListener(new JobEventListener() {
            @Override
            public void onStart(UUID jobId) {
                started.countDown();
            }
        });

        // ВАЖНО: runImmediately = false
        scheduler.addJob(veryRareCron, token -> {
        }, /*disallowOverlap*/ true, /*runImmediately*/ false);

        // В ближайшую секунду старта быть не должно
        assertFalse(started.await(1, TimeUnit.SECONDS), "Запуск не должен происходить сразу при runImmediately=false");
    }

    @Test
    void runImmediately_respectsDisallowOverlap_withNextCronTick() throws Exception {
        // частый cron, чтобы следующий тик пришёл быстро
        String everySecond = "*/1 * * * * *";

        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch finishedOnce = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder().build();

        // runImmediately = true, задача > 1с, чтобы «накрылся» следующий тик
        scheduler.addJob(everySecond, token -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(1200); // дольше секунды — следующий тик точно придёт
            } finally {
                concurrent.decrementAndGet();
                finishedOnce.countDown();
            }
        }, /*disallowOverlap*/ true, /*runImmediately*/ true);

        // Ждём завершения первого запуска
        assertTrue(finishedOnce.await(3, TimeUnit.SECONDS), "Первый запуск не завершился вовремя");
        // Проверяем, что параллелизма не было
        assertEquals(1, maxConcurrent.get(), "При disallowOverlap не должно быть параллельных запусков");
    }
}