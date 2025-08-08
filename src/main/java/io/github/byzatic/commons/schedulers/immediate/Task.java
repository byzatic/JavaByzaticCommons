package io.github.byzatic.commons.schedulers.immediate;

/**
 * Интерфейс задачи. Обязательно периодически проверяйте токен!
 */
public interface Task {
    void run(CancellationToken token) throws Exception;

    /**
     * Вызывается сразу при запросе мягкой остановки (опционально).
     */
    default void onStopRequested() {
    }
}
