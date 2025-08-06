package io.github.byzatic.commons.schedulers.cron_job_scheduler.proxy;

import io.github.byzatic.commons.schedulers.StatusInterface;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Status implements StatusInterface {
    private final StatusCode statusCode;
    private final Throwable faultCause;

    public Status(StatusCode statusCode, Throwable faultCause) {
        this.statusCode = statusCode;
        this.faultCause = faultCause;
    }

    public Status() {
        this.statusCode = StatusCode.NEVER_RUN;
        this.faultCause = null;
    }

    public StatusCode getStatus() {
        return statusCode;
    }

    public @Nullable Throwable getFaultCause() {
        return faultCause;
    }

    public static @NotNull Status running() {
        return new Status(StatusCode.RUNNING, null);
    }

    public static @NotNull Status complete() {
        return new Status(StatusCode.COMPLETE, null);
    }

    public static @NotNull Status fault(@NotNull Throwable cause) {
        return new Status(StatusCode.FAULT, cause);
    }

    public enum StatusCode {
        RUNNING,
        COMPLETE,
        FAULT,
        NEVER_RUN
    }
}
