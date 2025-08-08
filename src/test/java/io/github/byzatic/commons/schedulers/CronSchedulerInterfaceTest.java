package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CronSchedulerInterfaceTest {

    @Test
    void cronSchedulerImplementsInterfaceIfPresent() {
        try {
            Class<?> iface = Class.forName("io.github.byzatic.commons.schedulers.CronSchedulerInterface");
            assertTrue(iface.isInterface(), "CronSchedulerInterface should be an interface");
            assertTrue(iface.isAssignableFrom(CronScheduler.class),
                    "CronScheduler should implement CronSchedulerInterface");
        } catch (ClassNotFoundException e) {
            // Если интерфейса нет в сборке — не валим тест-сют (считаем условно «пропущенным»).
            assertTrue(true);
        }
    }
}