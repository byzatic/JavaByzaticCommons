package io.github.byzatic.commons.schedulers;

import com.google.errorprone.annotations.ThreadSafe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.github.byzatic.commons.ObjectsUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ThreadSafe
public class TaskTaskStateControlObserver implements TaskStateControlObserverInterface {
    private final String uniqueId;

    @GuardedBy("this")
    private TaskStatusCode code;

    @GuardedBy("this")
    private String faultCauseMessage = "";

    public TaskTaskStateControlObserver(String uniqueId) {
        ObjectsUtils.requireNonNull(uniqueId, new IllegalArgumentException("id should be NotNull"));
        this.uniqueId = uniqueId;
        this.code = TaskStatusCode.UNINITIALIZED;
    }

    @Override
    public synchronized void setStatusRunning() {
        this.code = TaskStatusCode.RUNNING;
        this.faultCauseMessage = "";
    }

    @Override
    public synchronized void setStatusComplete() {
        this.code = TaskStatusCode.COMPLETE;
        this.faultCauseMessage = "";
    }

    @Override
    public synchronized void setStatusFault(@Nullable String message) {
        this.code = TaskStatusCode.FAULT;
        this.faultCauseMessage = message == null ? "" : message;
    }

    @Override
    public synchronized @NotNull TaskStatusCode getStatus() {
        return code;
    }

    @Override
    public synchronized @NotNull String getFaultCauseMessage() {
        return faultCauseMessage;
    }

    @Override
    public @NotNull String getUniqueId() {
        return uniqueId;
    }

}
