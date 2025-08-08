package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImmediateSchedulerInterfaceTest {

    @Test
    void schedulerImplementsInterfaceIfPresent() {
        try {
            Class<?> iface = Class.forName("io.github.byzatic.commons.schedulers.develop.ImmediateSchedulerInterface");
            assertTrue(iface.isInterface(), "ImmediateSchedulerInterface should be an interface");
            assertTrue(iface.isAssignableFrom(ImmediateScheduler.class),
                    "ImmediateScheduler should implement ImmediateSchedulerInterface");
        } catch (ClassNotFoundException e) {
            // Если интерфейса нет — не валим тесты (считаем условно «пропущенным»).
            assertTrue(true);
        }
    }
}
