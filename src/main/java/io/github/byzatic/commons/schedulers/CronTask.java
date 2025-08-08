package io.github.byzatic.commons.schedulers;

/**
 * Интерфейс задачи. Обязательно проверяйте токен!
 */
public interface CronTask {
    void run(CancellationToken token) throws Exception;

    /**
     * Вызывается при запросе мягкой остановки (опционально).
     */
    default void onStopRequested() {
    }
}
