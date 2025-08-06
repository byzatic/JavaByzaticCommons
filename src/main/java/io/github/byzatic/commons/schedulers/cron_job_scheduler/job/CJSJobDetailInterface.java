package io.github.byzatic.commons.schedulers.cron_job_scheduler.job;

import io.github.byzatic.commons.schedulers.JobDetailInterface;
import io.github.byzatic.commons.schedulers.cron_job_scheduler.proxy.StatusProxy;
import org.jetbrains.annotations.NotNull;

import java.util.Date;

public interface CJSJobDetailInterface extends JobDetailInterface {
    @NotNull String getUniqueId();

    @NotNull Runnable getJob();

    Date getNextExecutionDate(Date now);

    @NotNull StatusProxy getStatusProxy();

    @Override
    boolean equals(Object o);

    @Override
    int hashCode();

    @Override
    String toString();
}
