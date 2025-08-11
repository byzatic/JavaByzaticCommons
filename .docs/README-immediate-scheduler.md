# ImmediateScheduler

`ImmediateScheduler` is a lightweight Java scheduler that **executes tasks immediately** upon submission. It supports cooperative cancellation, configurable grace periods, hard timeouts, and job lifecycle event notifications.

Package: `io.github.byzatic.commons.schedulers.immediate`

---

## Key Features

- **Immediate execution** — no cron expressions or scheduling delays.
- **Cooperative cancellation** via `CancellationToken` with custom stop reason.
- **Grace period** — allow tasks to finish cleanly before enforcing an interrupt.
- **Timeout enforcement** — tasks exceeding the grace period are interrupted and marked `TIMEOUT`.
- **Job lifecycle events** via `JobEventListener` (`onStart`, `onComplete`, `onCancelled`, `onTimeout`, `onError`).
- **Job state querying** via `JobInfo` objects.

---

## Core API

```java
public interface ImmediateSchedulerInterface extends AutoCloseable {
    UUID addTask(Task task);
    void stopTask(UUID jobId, Duration grace);
    Optional<JobInfo> query(UUID jobId);
    List<JobInfo> listJobs();
    void addListener(JobEventListener l);
    void removeListener(JobEventListener l);
}
```

### Task
A `Task` must implement:
```java
void run(CancellationToken token) throws Exception;
default void onStopRequested() {}
```

### CancellationToken
```java
boolean isStopRequested();
String reason();
void throwIfStopRequested() throws InterruptedException;
```

### JobState
`SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMEOUT`

---

## End-to-End Example

```java
package io.github.byzatic.commons.schedulers.develop;

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
            // Create task
            MyImmediateTask task = new MyImmediateTask();

            // Submit — starts immediately
            UUID jobId = scheduler.addTask(task);

            // Wait and request soft stop
            Thread.sleep(2500);
            logger.debug("[MAIN] Requesting stop...");
            scheduler.stopTask(jobId, Duration.ofSeconds(1));

            // Print final state
            scheduler.query(jobId).ifPresent(info ->
                    logger.debug("[MAIN] Final job state: " + info)
            );

            // Optional state check
            scheduler.query(jobId).ifPresent(info -> {
                if (info.state != JobState.CANCELLED && info.state != JobState.COMPLETED) {
                    logger.warn("[MAIN] Unexpected state: " + info.state);
                }
            });
        }
    }

    public static class MyEventListener implements JobEventListener {
        @Override public void onStart(UUID jobId)    { logger.debug("[EVENT] Job started: " + jobId); }
        @Override public void onComplete(UUID jobId) { logger.debug("[EVENT] Job completed: " + jobId); }
        @Override public void onCancelled(UUID jobId){ logger.debug("[EVENT] Job cancelled: " + jobId); }
        @Override public void onTimeout(UUID jobId)  { logger.debug("[EVENT] Job timed out!"); }
        @Override public void onError(UUID jobId, Throwable error) { logger.debug("[EVENT] Job error: " + error); }
    }

    public static class MyImmediateTask implements Task {
        private static final Logger log = LoggerFactory.getLogger(MyImmediateTask.class);
        private volatile boolean resourceOpen = false;

        @Override
        public void run(CancellationToken token) throws Exception {
            log.debug("Task started at " + Instant.now());
            resourceOpen = true;
            for (int i = 1; i <= 10; i++) {
                if (token.isStopRequested()) {
                    log.debug("Task stopping gracefully. Reason: " + token.reason());
                    cleanup();
                    return;
                }
                Thread.sleep(500);
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
```

---

## Usage Notes
- Always check `token.isStopRequested()` or call `token.throwIfStopRequested()` periodically.
- Use `onStopRequested()` for immediate cleanup when a stop signal is sent.
- Provide an appropriate thread pool size if your tasks may run concurrently.
- Job information (`JobInfo`) can be queried at any time to check current or final state.
