package io.github.byzatic.commons.schedulers;

import org.jetbrains.annotations.NotNull;

public interface CronTask extends Task{
    @NotNull String getCronExpressionString();
}
