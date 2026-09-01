package io.github.byzatic.commons.schedulers.unified;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface RunHandle {
    UUID id();
    Optional<UUID> scheduleId();
    RunState state();
    boolean requestCancellation(String reason);
    boolean cancel(String reason, Duration grace) throws InterruptedException;
    RunOutcome await() throws InterruptedException, ExecutionException;
    RunOutcome await(Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException;
    CompletionStage<RunOutcome> completion();
}
