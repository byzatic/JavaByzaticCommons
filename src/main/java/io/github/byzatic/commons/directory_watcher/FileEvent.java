package io.github.byzatic.commons.directory_watcher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable file event representation.
 */
public final class FileEvent {

    public enum EventType {
        CREATED, MODIFIED, DELETED, ANY
    }

    private final Path path;
    private final EventType type;
    private final Instant timestamp;

    public FileEvent(Path path, EventType type) {
        this(path, type, Instant.now());
    }

    public FileEvent(Path path, EventType type, Instant timestamp) {
        this.path = Objects.requireNonNull(path, "path");
        this.type = Objects.requireNonNull(type, "type");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public Path getPath() {
        return path;
    }

    public EventType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public static FileEvent created(Path path) {
        return new FileEvent(path, EventType.CREATED);
    }

    public static FileEvent modified(Path path) {
        return new FileEvent(path, EventType.MODIFIED);
    }

    public static FileEvent deleted(Path path) {
        return new FileEvent(path, EventType.DELETED);
    }

    public static FileEvent any(Path path) {
        return new FileEvent(path, EventType.ANY);
    }

    @Override
    public String toString() {
        return "FileEvent{" +
                "path=" + path +
                ", type=" + type +
                ", timestamp=" + timestamp +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileEvent)) return false;
        FileEvent that = (FileEvent) o;
        return Objects.equals(path, that.path) && type == that.type && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, type, timestamp);
    }
}
