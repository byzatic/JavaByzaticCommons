package io.github.byzatic.commons.directory_watcher;

import java.nio.file.Path;

/**
 * Listener interface to receive file system change events.
 * Implementations should be fast and non-blocking.
 */
public interface DirectoryChangeListener {
    void onFileCreated(Path path);
    void onFileModified(Path path);
    void onFileDeleted(Path path);
    /** Called for every event after the specific callback above. */
    void onAny(Path path);
}
