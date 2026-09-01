package io.github.byzatic.commons.schedulers.unified;

import io.github.byzatic.commons.schedulers.unified.internal.core.SchedulerRuntimeContext;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SchedulerRun;
import io.github.byzatic.commons.schedulers.unified.internal.execution.SerialExecutionLane;
import io.github.byzatic.commons.schedulers.unified.internal.executor.SchedulerExecutorFactory;
import io.github.byzatic.commons.schedulers.unified.internal.executor.SchedulerThreadFactory;
import io.github.byzatic.commons.schedulers.unified.internal.scheduling.SchedulerSchedule;
import io.github.byzatic.commons.schedulers.unified.internal.support.SchedulerIdentifiers;
import io.github.byzatic.commons.schedulers.unified.internal.timing.SchedulerTrigger;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UnifiedSchedulerArchitectureTest {
    @Test
    public void implementationModulesAreSeparatedFromPublicApi() {
        assertInternal(SchedulerRuntimeContext.class);
        assertInternal(SchedulerRun.class);
        assertInternal(SerialExecutionLane.class);
        assertInternal(SchedulerSchedule.class);
        assertInternal(SchedulerTrigger.class);
        assertInternal(SchedulerExecutorFactory.class);
        assertInternal(SchedulerThreadFactory.class);
        assertInternal(SchedulerIdentifiers.class);
    }

    private static void assertInternal(Class<?> type) {
        assertTrue(
                type.getName() + " must remain under the internal namespace",
                type.getPackageName().startsWith(
                        "io.github.byzatic.commons.schedulers.unified.internal."
                )
        );
    }
}
