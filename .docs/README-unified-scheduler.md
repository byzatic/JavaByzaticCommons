# UnifiedScheduler

`UnifiedScheduler` provides one lifecycle and execution model for immediate, delayed, periodic,
and cron tasks. Every user task runs on `ThreadPoolExecutor`; a timer dispatcher only transfers
due triggers into the executor queue.

## Construction

```java
UnifiedScheduler scheduler = UnifiedScheduler.builder()
        .singleThreaded()
        .queueCapacity(1024)
        .threadNamePrefix("project-reload")
        .timerThreadName("project-reload-timer")
        .shutdownPolicy(ShutdownPolicy.builder()
                .gracefulTimeout(Duration.ofSeconds(30))
                .forcedTimeout(Duration.ofSeconds(5))
                .build())
        .build();
```

Use `parallelism(n)` instead of `singleThreaded()` for concurrent workloads. The built-in executor
uses a bounded queue and `ThreadPoolExecutor.AbortPolicy`; rejected submissions throw
`RejectedExecutionException` and never run on the submitting or timer thread.

## Scheduling

```java
RunHandle immediate = scheduler.submit(token -> execute());

RunHandle delayed = scheduler.schedule(
        token -> checkTimeout(),
        Duration.ofSeconds(30));

ScheduleHandle fixedDelay = scheduler.schedule(
        token -> poll(),
        Schedules.fixedDelay(Duration.ZERO, Duration.ofSeconds(5)));

ScheduleHandle cron = scheduler.schedule(
        token -> calculateGraph(),
        Schedules.cron("0 */5 * * * *", ZoneId.of("UTC")),
        ScheduleOptions.builder()
                .overlapPolicy(OverlapPolicy.SKIP)
                .misfirePolicy(MisfirePolicy.SKIP)
                .failurePolicy(FailurePolicy.CONTINUE)
                .build());
```

`RunHandle` represents one execution and provides cancellation, bounded waiting, terminal state,
and the original failure. `ScheduleHandle` represents a recurring schedule and provides pause,
resume, cancellation, next-fire time, active runs, and the last outcome.

Completed one-shot runs are removed from scheduler-owned registries. Recurring schedules retain
only their active runs and last outcome.

## Cancellation and shutdown

Tasks should periodically call `CancellationContext.throwIfCancellationRequested()` or inspect
`isCancellationRequested()`. Cancellation first invokes `onCancellationRequested()` and signals
the context; after the requested grace period it interrupts the runner and reports `TIMED_OUT`.

`shutdown()` rejects new work and lets submitted work drain. `shutdownNow()` cancels waiting and
queued work, requests cancellation of running work, and interrupts executor threads.
`awaitTermination(Duration)` confirms actual executor termination.

## Legacy facades

The existing builders create facade-owned unified engines:

```java
ImmediateScheduler immediate = new ImmediateScheduler.Builder().build();
CronScheduler cron = new CronScheduler.Builder().build();
```

Both facades can also borrow one shared engine. Closing a borrowed facade cancels only work created
through that facade and does not close the engine:

```java
UnifiedScheduler unified = UnifiedScheduler.builder().parallelism(8).build();
ImmediateScheduler immediate = ImmediateScheduler.adapt(unified);
CronScheduler cron = CronScheduler.adapt(
        unified, ZoneId.systemDefault(), Duration.ofSeconds(10));
```
