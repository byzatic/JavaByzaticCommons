package io.github.byzatic.commons.schedulers.cron_job_scheduler.proxy;

import com.google.errorprone.annotations.ThreadSafe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.github.byzatic.commons.ObjectsUtils;
import io.github.byzatic.commons.schedulers.StatusInterface;
import io.github.byzatic.commons.schedulers.StatusProxyInterface;
import org.jetbrains.annotations.NotNull;

@ThreadSafe
public class StatusProxy implements StatusProxyInterface {
    private final String id;
    @GuardedBy("this") private StatusInterface status = null;

    public StatusProxy(String id) {
        this.id = id;
        this.status = new Status();
    }

    @NotNull
    public synchronized StatusInterface getStatusResult() throws IllegalStateException {
        ObjectsUtils.requireNonNull(status, new IllegalStateException("Status was not set"));
        return status;
    }

    public synchronized void setStatus(@NotNull StatusInterface status) {
        ObjectsUtils.requireNonNull(status, new IllegalArgumentException("Status must be NotNull"));
        this.status = status;
    }

    public String getId() {
        return id;
    }
}
