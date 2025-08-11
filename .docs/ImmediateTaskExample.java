import io.github.byzatic.commons.schedulers.immediate.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class ImmediateTaskExample {
    private static final Logger logger = LoggerFactory.getLogger(ImmediateTaskExample.class);

    public static void main(String[] args) throws Exception {
        try (ImmediateSchedulerInterface scheduler = new ImmediateScheduler.Builder()
                .defaultGrace(Duration.ofSeconds(3))
                .addListener(new MyEventListener())
                .build()
        ) {
            // Создаём задачу
            MyImmediateTask task = new MyImmediateTask();

            // Добавляем — она стартует СРАЗУ
            UUID jobId = scheduler.addTask(task);

            // Ждём 2.5 секунды и просим мягко остановиться (grace = 1 сек)
            Thread.sleep(2500);
            logger.debug("[MAIN] Requesting stop...");
            scheduler.stopTask(jobId, Duration.ofSeconds(1));

            // Печатаем состояние
            scheduler.query(jobId).ifPresent(info ->
                    logger.debug("[MAIN] Final job state: " + info)
            );

            // (Опционально) проверим, что отменили
            scheduler.query(jobId).ifPresent(info -> {
                if (info.state != JobState.CANCELLED && info.state != JobState.COMPLETED) {
                    logger.warn("[MAIN] Unexpected state: " + info.state);
                }
            });
        }
    }

    /**
     * Класс листнера событий
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
     * Класс задачи для ImmediateScheduler
     */
    public static class MyImmediateTask implements Task {
        private static final Logger log = LoggerFactory.getLogger(MyImmediateTask.class);
        private volatile boolean resourceOpen = false;

        @Override
        public void run(CancellationToken token) throws Exception {
            log.debug("Task started at " + Instant.now());
            // "Открываем" ресурс
            resourceOpen = true;

            // Работаем 10 шагов по ~500мс
            for (int i = 1; i <= 10; i++) {
                if (token.isStopRequested()) {
                    log.debug("Task stopping gracefully. Reason: " + token.reason());
                    cleanup();
                    return;
                }
                Thread.sleep(500); // имитация работы
                log.debug("Step " + i);
            }
            cleanup();
        }

        @Override
        public void onStopRequested() {
            log.debug("onStopRequested(): immediate reaction");
            if (resourceOpen) {
                log.debug("Closing resource immediately from onStopRequested()");
                resourceOpen = false;
            }
        }

        private void cleanup() {
            if (resourceOpen) {
                log.debug("Cleaning up resource at the end of task");
                resourceOpen = false;
            }
        }
    }
}
