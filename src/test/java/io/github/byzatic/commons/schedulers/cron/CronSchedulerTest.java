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

    //    Воспроизводится гонка: вы запускаете cron */1 * * * * * с runImmediately=true.
    //После stopJob(..., 50ms) requestStop() выставляет TIMEOUT и шлёт onTimeout,
    // но очень быстро (до 1 секунды) приходит следующий тик, submitRun() запускает новый прогон и тут же ставит state=RUNNING.
    // Вы успеваете увидеть уже второй прогон, отсюда Actual: RUNNING.
    @Test
    void timeoutDoesNotFlipToCancelled_andNoDuplicateEvents() throws Exception {
        String veryRareCron = "0 0 0 1 1 0"; // НГ в вск 00:00 — следующий тик очень нескоро

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger timeoutEvents = new AtomicInteger(0);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onTimeout(UUID jobId) { timeoutEvents.incrementAndGet(); timedOut.countDown(); }
                    @Override public void onCancelled(UUID jobId) { cancelled.countDown(); }
                })
                .defaultGrace(Duration.ofMillis(50))
                .build();

        UUID id = scheduler.addJob(veryRareCron, token -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
        }, /*disallowOverlap*/ true, /*runImmediately*/ true);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        scheduler.stopJob(id, Duration.ofMillis(50));

        assertTrue(timedOut.await(2, TimeUnit.SECONDS), "onTimeout не пришёл");

        // Дождаться, что раннер вышел из RUNNING (финализировался после interrupt)
        long deadline = System.currentTimeMillis() + 500;
        JobState state;
        do {
            state = scheduler.query(id).get().state;
            if (state != JobState.RUNNING) break;
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);

        assertEquals(JobState.TIMEOUT, state, "Статус не должен перезаписаться на CANCELLED");
        assertEquals(1, timeoutEvents.get(), "onTimeout должен быть единожды");
        assertFalse(cancelled.await(200, TimeUnit.MILLISECONDS), "После timeout не должен прилетать onCancelled");
    }

    @Test
    void removeJob_removesFromRegistry_queryBecomesEmpty() throws Exception {
        scheduler = new CronScheduler.Builder().build();
        UUID id = scheduler.addJob("*/1 * * * * *", token -> { /* быстрый таск */ }, true, true);
        // дать задаче стартануть/закончить
        Thread.sleep(100);

        assertTrue(scheduler.removeJob(id));
        assertTrue(scheduler.query(id).isEmpty(), "После remove запись должна пропасть из реестра");
        // повторное удаление — false
        assertFalse(scheduler.removeJob(id));
    }

    @Test
    void stopJob_afterCompleted_doesNotChangeFinalState() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onComplete(UUID jobId) { completed.countDown(); }
                })
                .build();

        UUID id = scheduler.addJob("*/1 * * * * *", token -> Thread.sleep(50), true, true);
        assertTrue(completed.await(2, TimeUnit.SECONDS), "Не дождались завершения задачи");

        // уже завершено — стоп не должен менять статус
        scheduler.stopJob(id, Duration.ofMillis(50));
        assertEquals(JobState.COMPLETED, scheduler.query(id).get().state, "Стоп после завершения не должен менять статус");
    }

    @Test
    void errorInTask_setsFailed_andOnErrorFires() throws Exception {
        CountDownLatch errored = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onError(UUID jobId, Throwable error) { errored.countDown(); }
                })
                .build();

        UUID id = scheduler.addJob("*/1 * * * * *", token -> { throw new IllegalStateException("boom"); }, true, true);

        assertTrue(errored.await(2, TimeUnit.SECONDS), "onError не пришёл");
        assertEquals(JobState.FAILED, scheduler.query(id).get().state);
        assertNotNull(scheduler.query(id).get().lastError);
    }

    @Test
    void stopJob_twice_afterTimeout_isIdempotent_andKeepsTimeoutState() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger timeoutEvents = new AtomicInteger(0);

        scheduler = new CronScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId) { started.countDown(); }
                    @Override public void onTimeout(UUID jobId) { timeoutEvents.incrementAndGet(); }
                })
                .defaultGrace(Duration.ofMillis(50))
                .build();

        UUID id = scheduler.addJob("*/1 * * * * *", token -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
        }, true, true);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        scheduler.stopJob(id, Duration.ofMillis(50));

        // подождать, пока выставится TIMEOUT
        Thread.sleep(150);
        assertEquals(JobState.TIMEOUT, scheduler.query(id).get().state);

        // повторный стоп не должен слать лишние события и менять статус
        scheduler.stopJob(id, Duration.ofMillis(50));
        Thread.sleep(100);

        assertEquals(JobState.TIMEOUT, scheduler.query(id).get().state);
        assertEquals(1, timeoutEvents.get(), "Повторный stop не должен дублировать onTimeout");
    }

    @Test
    void allowOverlap_allowsParallelRuns() throws Exception {
        // позволяем наложения: длительная задача 1.5с, cron каждую секунду → должен быть параллелизм >= 2
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch seenParallel = new CountDownLatch(1);

        scheduler = new CronScheduler.Builder().build();

        scheduler.addJob("*/1 * * * * *", token -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(1500);
            } finally {
                concurrent.decrementAndGet();
                if (maxConcurrent.get() >= 2) seenParallel.countDown();
            }
        }, /*disallowOverlap*/ false, /*runImmediately*/ true);

        assertTrue(seenParallel.await(5, TimeUnit.SECONDS), "Не увидели параллельного выполнения при allowOverlap");
        assertTrue(maxConcurrent.get() >= 2, "Должно быть >=2 параллельных запусков при allowOverlap=false");
    }
}