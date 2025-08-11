# 🐞 ISSUE: Job state may revert from TIMEOUT to RUNNING on frequent cron expressions

## Summary

A race condition occurs in `CronScheduler` when a job is stopped with `stopJob(...)` and its cron expression is very frequent (e.g., every second: `*/1 * * * * *`). If the job is configured with `runImmediately = true`, a second execution may start **immediately after the first one is interrupted**, overwriting the `TIMEOUT` state with `RUNNING`.

---

## Reproduction Steps

1. Schedule a job with:
   - `cron = "*/1 * * * * *"` (every second)
   - `runImmediately = true`
   - long-running body (e.g., `Thread.sleep(5000)`)
2. Call `stopJob(jobId, Duration.ofMillis(50))` after the first execution starts.
3. Wait ~1s for the next cron tick.
4. Query the job state.

### Observed Result
```java
JobState actual = scheduler.query(jobId).get().state;
// expected: TIMEOUT
// actual:   RUNNING
```

The second cron tick re-schedules the task and resets state to `RUNNING`.

---

## Expected Behavior

Once a job enters a terminal state like `TIMEOUT`, no future execution should override it without explicit user intervention (e.g., removal and re-addition of the job).

---

## Technical Root Cause

- The job is stopped by `stopJob()`, which interrupts the thread and sets state = `TIMEOUT`.
- Before the thread fully terminates and the test checks the state, the next cron tick arrives.
- The scheduler submits a new run (because the job still exists and is eligible), which updates the state to `RUNNING`.

This causes test assertions like the following to intermittently fail:
```java
assertEquals(JobState.TIMEOUT, scheduler.query(id).get().state);
```

---

## Recommended Fixes

### Option 1: Prevent Future Execution After Timeout
In `JobRecord`, introduce a flag like `terminalEventSent`:
```java
AtomicBoolean terminalEventSent = new AtomicBoolean(false);
```
Gate any new executions:
```java
if (record.terminalEventSent.get()) return;
```

### Option 2: Require Job Removal After Terminal States
Automatically deregister jobs on terminal states (`TIMEOUT`, `CANCELLED`, `FAILED`, `COMPLETED`), or prevent re-submission.

### Option 3: Adjust Tests
Make tests resilient by:
- Using very rare cron (e.g., `"0 0 0 1 1 0"`) to prevent accidental re-execution.
- Waiting for `JobState != RUNNING` before asserting the final state.

---

## Related Test Affected

- `timeoutDoesNotFlipToCancelled_andNoDuplicateEvents` in `CronSchedulerTest`

---

## Priority

🟡 **Medium** — does not affect correctness in production, but introduces **test instability** and confusing state transitions.

---

## Labels

- `race-condition`
- `cron-scheduler`
- `state-management`
- `test-instability`
