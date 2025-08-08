package io.github.byzatic.commons.schedulers.develop;


import io.github.byzatic.commons.schedulers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class CronTaskExample {
    private final static Logger logger = LoggerFactory.getLogger(CronTaskExample.class);

    public static void main(String[] args) throws Exception {
        try (CronSchedulerInterface scheduler = new CronScheduler.Builder()
                .defaultGrace(Duration.ofSeconds(5))
                .addListener(new MyEventListener())
                .build()
        ) {
            // Создаём задачу
            MyCronTask task = new MyCronTask();

            // Запускаем каждые 10 секунд (теперь 6 полей cron)
            UUID jobId = scheduler.addJob("*/10 * * * * *", task, true, true);

            // Ждём 15 секунд и посылаем команду на остановку
            Thread.sleep(15000);
            logger.debug("[MAIN] Requesting stop...");
            scheduler.stopJob(jobId, Duration.ofSeconds(3));

            // Печатаем состояние
            scheduler.query(jobId).ifPresent(info ->
                    logger.debug("[MAIN] Final job state: " + info)
            );
        }
    }

    /**
     * Класс листнера, реализующей JobEventListener
     */
    public static class MyEventListener implements JobEventListener {
        @Override
        public void onStart(UUID jobId) {
            logger.debug("[EVENT] Job started: " + jobId);
        }

        @Override
        public void onComplete(UUID jobId) {
            logger.debug("[EVENT] Job completed: " + jobId);
        }

        @Override
        public void onCancelled(UUID jobId) {
            logger.debug("[EVENT] Job cancelled: " + jobId);
        }

        @Override
        public void onTimeout(UUID jobId) {
            logger.debug("[EVENT] Job timed out!");
        }

        @Override
        public void onError(UUID jobId, Throwable error) {
            logger.debug("[EVENT] Job error: " + error);
        }
    }


    /**
     * Класс задачи, реализующей CronTask
     */
    public static class MyCronTask implements CronTask {
        private final static Logger logger = LoggerFactory.getLogger(MyCronTask.class);
        private volatile boolean resourceOpen = false;

        @Override
        public void run(CancellationToken token) throws Exception {
            logger.debug("Task started at " + Instant.now());
            // "Открываем" ресурс
            resourceOpen = true;

            // Работаем 10 шагов
            for (int i = 1; i <= 10; i++) {
                // Проверяем, не пришла ли команда на стоп
                if (token.isStopRequested()) {
                    logger.debug("Task stopping gracefully. Reason: " + token.reason());
                    cleanup();
                    return;
                }
                Thread.sleep(1000); // имитация работы
                logger.debug("Step " + i);
            }
            cleanup();
        }

        @Override
        public void onStopRequested() {
            logger.debug("onStopRequested(): immediate reaction");
            if (resourceOpen) {
                logger.debug("Closing resource immediately from onStopRequested()");
                resourceOpen = false;
            }
        }

        private void cleanup() {
            if (resourceOpen) {
                logger.debug("Cleaning up resource at the end of task");
                resourceOpen = false;
            }
        }
    }
}