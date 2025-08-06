package io.github.byzatic.commons.schedulers.cron_job_scheduler.job;

import io.github.byzatic.commons.schedulers.cron_job_scheduler.proxy.StatusProxy;
import io.github.byzatic.commons.schedulers.cron_job_scheduler.common.CronDateCalculator;
import org.jetbrains.annotations.NotNull;

import java.text.ParseException;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

public class CJSJobDetail implements CJSJobDetailInterface {
    private String uniqueId;
    private Runnable job;
    private CronDateCalculator calculator;
    private StatusProxy statusProxy;

    public CJSJobDetail() {
    }

    private CJSJobDetail(Builder builder) {
        uniqueId = builder.uniqueId;
        job = builder.job;
        calculator = builder.calculator;
        statusProxy = builder.statusProxy;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Builder newBuilder(CJSJobDetail copy) {
        Builder builder = new Builder();
        builder.uniqueId = copy.uniqueId;
        builder.job = copy.job;
        builder.calculator = copy.calculator;
        builder.statusProxy = copy.statusProxy;
        return builder;
    }


    @Override
    public @NotNull String getUniqueId() {
        return uniqueId;
    }

    @Override
    public @NotNull Runnable getJob() {
        return job;
    }

    @Override
    public Date getNextExecutionDate(Date now) {
        return calculator.getNextExecutionDate(now);
    }

    @Override
    public @NotNull StatusProxy getStatusProxy() {
        return statusProxy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CJSJobDetail jobDetail = (CJSJobDetail) o;
        return Objects.equals(uniqueId, jobDetail.uniqueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId);
    }

    @Override
    public String toString() {
        return "JobDetail{" +
                "uniqueId='" + uniqueId + '\'' +
                ", job=" + job +
                ", calculator=" + calculator +
                ", statusProxy=" + statusProxy +
                '}';
    }

    /**
     * {@code JobDetail} builder static inner class.
     */
    public static final class Builder {
        private String uniqueId = UUID.randomUUID().toString();
        private Runnable job;
        private CronDateCalculator calculator;
        private StatusProxy statusProxy = new StatusProxy(uniqueId);

        private Builder() {
        }

        /**
         * Sets the {@code uniqueId} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param uniqueId the {@code uniqueId} to set
         * @return a reference to this Builder
         */
        public Builder setUniqueId(String uniqueId) {
            this.uniqueId = uniqueId;
            return this;
        }

        /**
         * Sets the {@code job} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param job the {@code job} to set
         * @return a reference to this Builder
         */
        public Builder setJob(Runnable job) {
            this.job = job;
            return this;
        }

        /**
         * Sets the {@code calculator} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param cronExpressionString the {@code cronExpressionString} to set {@code calculator}
         * @return a reference to this Builder
         */
        public Builder setCronExpressionString(String cronExpressionString) throws ParseException {
            this.calculator = new CronDateCalculator(cronExpressionString);
            return this;
        }

        /**
         * Sets the {@code statusProxy} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param statusProxy the {@code statusProxy} to set
         * @return a reference to this Builder
         */
        public Builder setStatusProxy(StatusProxy statusProxy) {
            this.statusProxy = statusProxy;
            return this;
        }

        /**
         * Returns a {@code JobDetail} built from the parameters previously set.
         *
         * @return a {@code JobDetail} built with parameters of this {@code JobDetail.Builder}
         */
        public CJSJobDetail build() {
            return new CJSJobDetail(this);
        }
    }
}
