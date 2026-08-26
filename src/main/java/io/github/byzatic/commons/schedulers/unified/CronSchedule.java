package io.github.byzatic.commons.schedulers.unified;

import java.time.ZoneId;
import java.util.Objects;

public final class CronSchedule implements Schedule {
    private final CronExpression expression;
    private final ZoneId zone;
    private final boolean runImmediately;

    CronSchedule(CronExpression expression, ZoneId zone, boolean runImmediately) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.runImmediately = runImmediately;
    }

    public CronExpression expression() { return expression; }
    public ZoneId zone() { return zone; }
    public boolean runImmediately() { return runImmediately; }
}
