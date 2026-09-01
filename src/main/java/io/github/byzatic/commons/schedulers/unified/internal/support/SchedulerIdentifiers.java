package io.github.byzatic.commons.schedulers.unified.internal.support;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApiStatus.Internal
public final class SchedulerIdentifiers {
    private SchedulerIdentifiers() {
        throw new AssertionError("No instances");
    }

    public static UUID newIdentifier() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSignificantBits = random.nextLong();
        long leastSignificantBits = random.nextLong();
        mostSignificantBits = (mostSignificantBits & 0xffffffffffff0fffL) | 0x0000000000004000L;
        leastSignificantBits = (leastSignificantBits & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
