package io.github.byzatic.commons;


import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Utility class for generating UUIDs.
 */
public class UuidProvider {

    /**
     * Generates a new random UUID.
     *
     * @return a new {@link UUID}
     */
    public static @NotNull UUID generateUuid() {
        return UUID.randomUUID();
    }

    /**
     * Generates a UUID and returns it as a string.
     *
     * @return a new UUID as a {@link String}
     */
    public static @NotNull String generateUuidString() {
        return UUID.randomUUID().toString();
    }
}
