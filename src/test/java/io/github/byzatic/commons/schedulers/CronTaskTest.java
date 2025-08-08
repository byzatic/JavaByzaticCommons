package io.github.byzatic.commons.schedulers;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CronTaskTest {

    static class MyTask implements CronTask {
        final AtomicBoolean ran = new AtomicBoolean(false);
        final AtomicBoolean stopHook = new AtomicBoolean(false);
        @Override public void run(CancellationToken token) {
            ran.set(true);
            assertNotNull(token);
        }
        @Override public void onStopRequested() { stopHook.set(true); }
    }

    @Test
    void runAcceptsToken_andStopHookCallable() throws Exception {
        MyTask t = new MyTask();
        t.run(new CancellationToken());
        assertTrue(t.ran.get());
        t.onStopRequested();
        assertTrue(t.stopHook.get());
    }
}