package io.github.byzatic.commons.schedulers;

import org.jetbrains.annotations.NotNull;

public interface JobDetailInterface {
    @NotNull
    String getUniqueId();

    @NotNull
    Runnable getJob();

    @NotNull
    StatusProxyInterface getStatusProxy();
}
