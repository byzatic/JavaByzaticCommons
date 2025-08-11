# Cron-based Scheduler

A lightweight, embeddable scheduler for running recurring jobs defined by **cron expressions** (with optional seconds). It is designed for server-side use: no reflection, no magic, just a small API and predictable behavior.

## Key Features
- **5- or 6-field cron syntax** (seconds supported when 6 fields are provided).
- **Cooperative cancellation** via `CancellationToken` and **graceful stop** timeouts.
- **Overlap control**: disallow parallel runs of the same job.
- **Run-now** option in addition to cron schedule.
- **Event listeners** for starts, completions, errors, timeouts, cancellations.
- **Queryable state**: list jobs and inspect last start/end/error.
- **Pluggable executor & timezone**: bring your own `ThreadPoolExecutor`, pick `ZoneId`.

## Modules (public types)
- `CronSchedulerInterface` — main API to register/manage jobs.
- `CronScheduler` — default implementation.
- `CronTask` — your job contract: `void run(CancellationToken token)`.
- `CancellationToken` — cooperative stop signal: check `isStopRequested()` or call `throwIfStopRequested()`.
- `JobEventListener` — lifecycle callbacks.
- `JobInfo`, `JobState` — read-only job metadata and state.
- `CronExpr` — parser and next-fire-time calculator (supports seconds).
- `ScheduledEntry` — internal delay-queue entry (not used directly).

Package: `io.github.byzatic.commons.schedulers.cron`.

## Cron Syntax
Supports classic 5-field cron (`min hour dom mon dow`) and 6-field cron with seconds (`sec min hour dom mon dow`). Ranges (`1-5`), lists (`1,2,3`), steps (`*/5`, `1-10/2`) are supported. `dow` uses `0..6` where `0=Sunday`.

Examples:
- `*/10 * * * * *` — every 10 seconds.
- `0 */5 * * * *` — every 5 minutes at second `0`.
- `0 0 2 * * *` — daily at 02:00:00.
- `0 30 9 * * 1-5` — 09:30:00 on weekdays.

## Quick Start

```java
import io.github.byzatic.commons.schedulers.cron.*;
import java.time.ZoneId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class Example {
    public static void main(String[] args) throws Exception {
        // 1) Your executor (configure as you need)
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(r, "cron-worker"));

        // 2) Optional listeners
        JobEventListener logListener = new JobEventListener() {
            public void onStart(UUID id)     { System.out.println(id + " started"); }
            public void onComplete(UUID id)  { System.out.println(id + " completed"); }
            public void onError(UUID id, Throwable e) { e.printStackTrace(); }
            public void onTimeout(UUID id)   { System.err.println(id + " timeout"); }
            public void onCancelled(UUID id) { System.out.println(id + " cancelled"); }
        };

        // 3) Construct the scheduler
        // Constructor signature (from source):
        // CronScheduler(ThreadPoolExecutor executor, ZoneId zone, long defaultGraceMillis, List<JobEventListener> listeners)
        CronScheduler scheduler = new CronScheduler(
                executor, ZoneId.systemDefault(), Duration.ofSeconds(30).toMillis(), List.of(logListener)
        );

        // 4) Define a task
        CronTask task = token -> {
            for (int i = 0; i < 10; i++) {
                token.throwIfStopRequested(); // cooperative stop check
                // do work ...
                Thread.sleep(500);
            }
        };

        // 5) Schedule: every minute, disallow overlapping, and run immediately once
        UUID jobId = scheduler.addJob("0 * * * * *", task, /*disallowOverlap*/ true, /*runImmediately*/ true);

        // Later: query, stop gracefully, or remove
        JobInfo info = scheduler.query(jobId).orElseThrow();
        System.out.println("Job state: " + info.state);

        // Request a soft stop with a 5s grace window
        scheduler.stopJob(jobId, Duration.ofSeconds(5));

        // Or remove (with/without grace)
        scheduler.removeJob(jobId, Duration.ofSeconds(5));

        // Shutdown when your app stops
        scheduler.close();
        executor.shutdown();
    }
}
```

## Full Example

```java
import io.github.byzatic.commons.schedulers.cron.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class CronTaskExample {
    private final static Logger logger = LoggerFactory.getLogger(CronTaskExample.class);

    public static void main(String[] args) throws Exception {
        try (CronSchedulerInterface scheduler = new CronScheduler.Builder()
                .defaultGrace(Duration.ofSeconds(5))
                .addListener(new MyEventListener())
                .build()
        ) {
            // Create task
            MyCronTask task = new MyCronTask();

            // Run every 10 seconds (6-field cron)
            UUID jobId = scheduler.addJob("*/10 * * * * *", task, true, true);

            // Wait 15 seconds then request stop
            Thread.sleep(15000);
            logger.debug("[MAIN] Requesting stop...");
            scheduler.stopJob(jobId, Duration.ofSeconds(3));

            // Print final state
            scheduler.query(jobId).ifPresent(info ->
                    logger.debug("[MAIN] Final job state: " + info)
            );
        }
    }

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

    public static class MyCronTask implements CronTask {
        private final static Logger logger = LoggerFactory.getLogger(MyCronTask.class);
        private volatile boolean resourceOpen = false;

        @Override
        public void run(CancellationToken token) throws Exception {
            logger.debug("Task started at " + Instant.now());
            resourceOpen = true;
            for (int i = 1; i <= 10; i++) {
                if (token.isStopRequested()) {
                    logger.debug("Task stopping gracefully. Reason: " + token.reason());
                    cleanup();
                    return;
                }
                Thread.sleep(1000);
                logger.debug("Step " + i);
            }
            cleanup();
        }

        @Override
        public void onStopRequested() {
            logger.debug("onStopRequested(): immediate reaction");
            if (resourceOpen) {
                logger.debug("Closing resource immediately from onStopRequested()");
                resourceOpen = false;
            }
        }

        private void cleanup() {
            if (resourceOpen) {
                logger.debug("Cleaning up resource at the end of task");
                resourceOpen = false;
            }
        }
    }
}
```

### Overlap Control
- `disallowOverlap = true` — if a previous run is still executing at the next trigger, that fire is **skipped**.
- `disallowOverlap = false` — every trigger produces a new run (parallel executions allowed).

### Cooperative Cancellation & Grace
- The scheduler signals cancellation by calling `CancellationToken.requestStop(...)` internally.
- Your job should regularly call `token.throwIfStopRequested()` or check `token.isStopRequested()`.
- If the job does not finish within the **grace** timeout, the scheduler interrupts the running thread and marks the job as `TIMEOUT`.

### Events
Implement `JobEventListener` to observe lifecycle:
- `onStart(UUID jobId)`
- `onComplete(UUID jobId)`
- `onError(UUID jobId, Throwable error)`
- `onTimeout(UUID jobId)`
- `onCancelled(UUID jobId)`

### Timezone
`CronScheduler` accepts a `ZoneId`. All next-fire computations use this zone.

## API Summary

```java
public interface CronSchedulerInterface extends AutoCloseable {
    void addListener(JobEventListener l);
    void removeListener(JobEventListener l);

    UUID addJob(String cron, CronTask task, boolean disallowOverlap, boolean runImmediately);
    UUID addJob(String cron, CronTask task);
    UUID addJob(String cron, CronTask task, boolean disallowOverlap);

    boolean removeJob(UUID jobId);
    boolean removeJob(UUID jobId, Duration grace);
    void stopJob(UUID jobId, Duration grace);

    Optional<JobInfo> query(UUID jobId);
    List<JobInfo> listJobs();
}
```

```java
public interface CronTask {
    void run(CancellationToken token) throws Exception;
    default void onStopRequested() {}
}
```

```java
public final class CancellationToken {
    public boolean isStopRequested();
    public String reason();
    public void throwIfStopRequested() throws InterruptedException;
}
```

```java
public enum JobState { SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT }
```

## Error Handling
- Exceptions thrown from `CronTask.run(...)` are caught and reported via `onError`, and the run is marked `FAILED`.
- If a job execution exceeds the grace period after a stop request, it is marked `TIMEOUT`.
- The scheduler remains operational even if individual jobs fail.


## End-to-end example (Builder API)

```java
package io.github.byzatic.commons.schedulers.develop;

import io.github.byzatic.commons.schedulers.cron.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class CronTaskExample {
    private static final Logger logger = LoggerFactory.getLogger(CronTaskExample.class);

    public static void main(String[] args) throws Exception {
        // Build the scheduler with a default grace timeout and an event listener.
        try (CronSchedulerInterface scheduler = new CronScheduler.Builder()
                .defaultGrace(Duration.ofSeconds(5))
                .addListener(new MyEventListener())
                .build()
        ) {
            // Create the task
            MyCronTask task = new MyCronTask();

            // Schedule every 10 seconds (6-field cron with seconds).
            UUID jobId = scheduler.addJob("*/10 * * * * *", task, /*disallowOverlap*/ true, /*runImmediately*/ true);

            // Wait 15 seconds and request a soft stop
            Thread.sleep(15_000);
            logger.debug("[MAIN] Requesting stop...");
            scheduler.stopJob(jobId, Duration.ofSeconds(3));

            // Print final state
            scheduler.query(jobId).ifPresent(info ->
                logger.debug("[MAIN] Final job state: " + info)
            );
        }
    }

    /** Listener implementing JobEventListener. */
    public static class MyEventListener implements JobEventListener {
        @Override public void onStart(UUID jobId)     { logger.debug("[EVENT] Job started: " + jobId); }
        @Override public void onComplete(UUID jobId)  { logger.debug("[EVENT] Job completed: " + jobId); }
        @Override public void onCancelled(UUID jobId) { logger.debug("[EVENT] Job cancelled: " + jobId); }
        @Override public void onTimeout(UUID jobId)   { logger.debug("[EVENT] Job timed out!"); }
        @Override public void onError(UUID jobId, Throwable error) { logger.debug("[EVENT] Job error: " + error); }
    }

    /** Task implementing CronTask. */
    public static class MyCronTask implements CronTask {
        private static final Logger logger = LoggerFactory.getLogger(MyCronTask.class);
        private volatile boolean resourceOpen = false;

        @Override
        public void run(CancellationToken token) throws Exception {
            logger.debug("Task started at " + Instant.now());
            // "Open" a resource
            resourceOpen = true;

            // Do 10 steps of work
            for (int i = 1; i <= 10; i++) {
                // Check for cooperative stop
                if (token.isStopRequested()) {
                    logger.debug("Task stopping gracefully. Reason: " + token.reason());
                    cleanup();
                    return;
                }
                Thread.sleep(1_000); // simulate work
                logger.debug("Step " + i);
            }
            cleanup();
        }

        @Override
        public void onStopRequested() {
            logger.debug("onStopRequested(): immediate reaction");
            if (resourceOpen) {
                logger.debug("Closing resource immediately from onStopRequested()");
                resourceOpen = false;
            }
        }

        private void cleanup() {
            if (resourceOpen) {
                logger.debug("Cleaning up resource at the end of task");
                resourceOpen = false;
            }
        }
    }
}
```

## Notes
- This scheduler is an in-process component; it does not persist jobs across JVM restarts.
- Provide an appropriately sized `ThreadPoolExecutor` for your workload.
- Backpressure: if `disallowOverlap=false` and jobs run longer than their periods, concurrent load will increase.

---

© 2025. MIT/Apache-2.0 as applicable to your repository (update this line to your actual license).
