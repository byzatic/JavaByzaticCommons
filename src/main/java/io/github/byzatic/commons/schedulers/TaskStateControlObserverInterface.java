package io.github.byzatic.commons.schedulers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TaskStateControlObserverInterface {
    void setStatusRunning();

    void setStatusComplete();

    void setStatusFault(@Nullable String message);

    @NotNull TaskStatusCode getStatus();

    @NotNull String getFaultCauseMessage();

    @NotNull String getUniqueId();
}
