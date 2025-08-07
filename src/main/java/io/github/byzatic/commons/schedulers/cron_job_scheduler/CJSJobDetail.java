package io.github.byzatic.commons.schedulers.cron_job_scheduler;

import io.github.byzatic.commons.UuidProvider;
import io.github.byzatic.commons.schedulers.TaskTaskStateControlObserver;
import io.github.byzatic.commons.schedulers.interfaces.CJSJobDetailInterface;
import org.jetbrains.annotations.NotNull;

import java.text.ParseException;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

public class CJSJobDetail implements CJSJobDetailInterface {
    private String uniqueId;
    private Runnable job;
    private CommonCronDateCalculator calculator;
    private TaskTaskStateControlObserver taskStateControlObserver;

    public CJSJobDetail() {
    }

    private CJSJobDetail(Builder builder) {
        job = builder.job;
        uniqueId = builder.uniqueId;
        calculator = builder.calculator;
        taskStateControlObserver = builder.taskStateControlObserver;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static Builder newBuilder(CJSJobDetail copy) {
        Builder builder = new Builder();
        builder.uniqueId = copy.uniqueId;
        builder.job = copy.job;
        builder.calculator = copy.calculator;
        builder.taskStateControlObserver = copy.taskStateControlObserver;
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
    public @NotNull TaskTaskStateControlObserver getStatusProxy() {
        return taskStateControlObserver;
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
                ", statusProxy=" + taskStateControlObserver +
                '}';
    }

    /**
     * {@code JobDetail} builder static inner class.
     */
    public static final class Builder {
        private String uniqueId = UUID.randomUUID().toString();
        private Runnable job;
        private CommonCronDateCalculator calculator;
        private TaskTaskStateControlObserver taskStateControlObserver = new TaskTaskStateControlObserver(uniqueId);

        private Builder() {
        }

        /**
         * Sets the {@code uniqueId} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param uniqueId the {@code uniqueId} to set
         * @return a reference to this Builder
         */
        public Builder setUniqueId(String uniqueId) {
            if (uniqueId == null) {
                this.uniqueId = UuidProvider.generateUuidString();
            } else {
                this.uniqueId = uniqueId;
            }
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
            this.calculator = new CommonCronDateCalculator(cronExpressionString);
            return this;
        }

        /**
         * Sets the {@code statusProxy} and returns a reference to this Builder so that the methods can be chained together.
         *
         * @param taskStateControlObserver the {@code statusProxy} to set
         * @return a reference to this Builder
         */
        public Builder setStatusProxy(TaskTaskStateControlObserver taskStateControlObserver) {
            this.taskStateControlObserver = taskStateControlObserver;
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
