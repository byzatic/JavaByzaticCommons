package io.github.byzatic.commons.schedulers.immediate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JobEventListenerTest {

    ImmediateScheduler scheduler;

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null) scheduler.close();
    }

    @Test
    void receivesStartAndComplete_andNoError() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        scheduler = new ImmediateScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onStart(UUID jobId)    { started.countDown(); }
                    @Override public void onComplete(UUID jobId) { completed.countDown(); }
                })
                .build();

        scheduler.addTask(token -> {});
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(completed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void errorInTask_firesOnError_andStateFailed() throws Exception {
        CountDownLatch errored = new CountDownLatch(1);

        scheduler = new ImmediateScheduler.Builder()
                .addListener(new JobEventListener() {
                    @Override public void onError(UUID jobId, Throwable error) { errored.countDown(); }
                }).build();

        UUID id = scheduler.addTask(token -> { throw new IllegalStateException("boom"); });
        assertTrue(errored.await(1, TimeUnit.SECONDS));
        assertEquals(JobState.FAILED, scheduler.query(id).get().state);
        assertNotNull(scheduler.query(id).get().lastError);
    }
}
