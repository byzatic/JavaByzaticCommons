package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

    static class MyTask implements Task {
        final AtomicBoolean ran = new AtomicBoolean(false);
        final AtomicBoolean stopHook = new AtomicBoolean(false);
        @Override public void run(CancellationToken token) {
            assertNotNull(token);
            ran.set(true);
        }
        @Override public void onStopRequested() { stopHook.set(true); }
    }

    @Test
    void runReceivesToken_andStopHookCallable() throws Exception {
        MyTask t = new MyTask();
        t.run(new CancellationToken());
        assertTrue(t.ran.get());
        t.onStopRequested();
        assertTrue(t.stopHook.get());
    }
}
