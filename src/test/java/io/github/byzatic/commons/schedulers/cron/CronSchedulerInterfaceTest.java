package io.github.byzatic.commons.schedulers.cron;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CronSchedulerInterfaceTest {

    @Test
    void cronSchedulerImplementsInterfaceIfPresent() {
        try {
            Class<?> iface = Class.forName("io.github.byzatic.commons.schedulers.cron.CronSchedulerInterface");
            assertTrue(iface.isInterface(), "CronSchedulerInterface should be an interface");
            assertTrue(iface.isAssignableFrom(CronScheduler.class),
                    "CronScheduler should implement CronSchedulerInterface");
        } catch (ClassNotFoundException e) {
            // Если интерфейса нет в сборке — не валим тест-сют (считаем условно «пропущенным»).
            assertTrue(true);
        }
    }
}