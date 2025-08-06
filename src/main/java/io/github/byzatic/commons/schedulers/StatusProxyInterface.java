package io.github.byzatic.commons.schedulers;

import com.google.errorprone.annotations.ThreadSafe;
import org.jetbrains.annotations.NotNull;

@ThreadSafe
public interface StatusProxyInterface {
    void setStatus(@NotNull StatusInterface status);

    @NotNull
    StatusInterface getStatusResult();
}
