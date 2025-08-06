package io.github.byzatic.commons.directory_watcher;

import com.google.common.annotations.Beta;
import com.google.errorprone.annotations.ThreadSafe;
import io.github.byzatic.commons.token_bucket_limiter.Limiter;
import io.github.byzatic.commons.token_bucket_limiter.SimpleTokenBucketLimiter;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A polling-based recursive watcher implemented on Apache Commons VFS.
 * Detects CREATED/MODIFIED/DELETED events for files under the given root.
 * Notes:
 * - This is a polling scanner (not OS inotify/KQueue). Choose a sensible polling interval.
 * - Supports global/per-path/pattern-based rate limiting (built-in token bucket, no Guava).
 * - Supports debounce windows globally, per-path, and by path matcher.
 * - Directories are not reported as events; only files are tracked.
 */
@Beta
@ThreadSafe
public class RecursiveVfsDirectoryWatcher implements Closeable {
    private final static Logger logger = LoggerFactory.getLogger(RecursiveVfsDirectoryWatcher.class);

    /* ============== Fields ============== */

    private final FileSystemManager fsManager;
    private final FileObject rootDir;
    private final Set<Path> excludedPaths;
    private final ScheduledExecutorService executor;
    private final ExecutorService eventDispatcher;
    private final DirectoryChangeListener listener;
    private final long pollingIntervalMillis;

    private final int maxQueueSize;
    private final BlockingQueue<FileEvent> eventQueue;

    private final Map<Path, FileInfo> knownFiles = new ConcurrentHashMap<>();

    private final Limiter globalRateLimiter; // may be null
    private final double rateLimitPerSecond;
    private final Map<Path, Limiter> rateLimiterPerPath;
    private final List<Map.Entry<PathMatcher, Limiter>> rateLimiterMatchers;

    private final long debounceWindowMillis;
    private final Map<Path, Long> lastEventTime = new ConcurrentHashMap<>();
    private final Map<Path, Long> debounceWindowPerPath;
    private final List<Map.Entry<PathMatcher, Long>> debounceWindowMatchers;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean shutdownHookEnabled;
    private Thread shutdownHookThread;

    private static final FileObject[] EMPTY = new FileObject[0];

    /* ============ Constructor ============ */

    private RecursiveVfsDirectoryWatcher(Builder b) throws FileSystemException {
        this.fsManager = (b.fsManager != null) ? b.fsManager : VFS.getManager();
        this.rootDir = this.fsManager.resolveFile(Objects.requireNonNull(b.rootUri, "rootUri"));
        if (!rootDir.exists() || !rootDir.isFolder()) {
            throw new FileSystemException("Root URI must exist and be a directory: " + b.rootUri);
        }
        this.excludedPaths = Set.copyOf(b.excludedPaths);
        this.listener = Objects.requireNonNull(b.listener, "listener");
        this.pollingIntervalMillis = b.pollingIntervalMillis;
        this.maxQueueSize = b.maxQueueSize;
        this.eventQueue = new ArrayBlockingQueue<>(this.maxQueueSize);

        this.rateLimitPerSecond = b.rateLimitPerSecond;
        this.globalRateLimiter = (rateLimitPerSecond > 0) ? new SimpleTokenBucketLimiter(rateLimitPerSecond) : null;
        this.rateLimiterPerPath = Map.copyOf(b.rateLimiterPerPath);
        this.rateLimiterMatchers = List.copyOf(b.rateLimiterMatchers);

        this.debounceWindowMillis = b.debounceWindowMillis;
        this.debounceWindowPerPath = Map.copyOf(b.debounceWindowPerPath);
        this.debounceWindowMatchers = List.copyOf(b.debounceWindowMatchers);

        this.shutdownHookEnabled = b.shutdownHookEnabled;

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vfs-watch-poller");
            t.setDaemon(true);
            return t;
        });
        this.eventDispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vfs-watch-dispatcher");
            t.setDaemon(true);
            return t;
        });

        // preload snapshot of known files
        primeKnownFiles();
    }

    /* ============ Lifecycle ============ */

    /**
     * Start polling. No-op if already running.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        if (shutdownHookEnabled) {
            shutdownHookThread = new Thread(this::closeQuietly, "vfs-watch-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        }

        // Event dispatcher
        eventDispatcher.submit(this::dispatchLoop);

        // Polling task — first run immediately (initial delay = 0)
        executor.scheduleAtFixedRate(() -> {
            try {
                scanOnce();
            } catch (Exception e) {
                // keep running on failure
                logger.error("Exception occurred, keep running on failure", e);
            }
        }, 0, pollingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Backward-compatibility alias.
     */
    public void startWatching() {
        start();
    }

    /**
     * Backward-compatibility alias.
     */
    public void stopWatching() {
        try {
            close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Stop polling and shutdown executors.
     */
    @Override
    public void close() throws IOException {
        if (!running.compareAndSet(true, false)) return;
        if (shutdownHookEnabled && shutdownHookThread != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHookThread);
            } catch (IllegalStateException ignore) {
                // JVM is shutting down
            }
        }
        executor.shutdownNow();
        eventDispatcher.shutdownNow();
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignore) {
        }
    }

    /* ============ Internals ============ */

    private void dispatchLoop() {
        while (running.get() || !eventQueue.isEmpty()) {
            try {
                FileEvent ev = eventQueue.poll(500, TimeUnit.MILLISECONDS);
                if (ev == null) continue;

                switch (ev.getType()) {
                    case CREATED:
                        listener.onFileCreated(ev.getPath());
                        break;
                    case MODIFIED:
                        listener.onFileModified(ev.getPath());
                        break;
                    case DELETED:
                        listener.onFileDeleted(ev.getPath());
                        break;
                    case ANY:
                        // no explicit ANY event enqueued; kept for compatibility
                        break;
                }
                listener.onAny(ev.getPath());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // swallow listener exceptions to keep loop alive
                logger.error("Exception occurred, swallow listener exceptions to keep loop alive", t);
            }
        }
    }

    private void primeKnownFiles() throws FileSystemException {
        Deque<FileObject> stack = new ArrayDeque<>();
        stack.push(rootDir);
        while (!stack.isEmpty()) {
            FileObject dir = stack.pop();
            for (FileObject fo : safeChildren(dir)) {
                if (fo.isFolder()) {
                    stack.push(fo);
                } else if (fo.isFile()) {
                    Path p = toLocalPath(fo);
                    if (isExcluded(p)) continue;
                    try {
                        knownFiles.put(p, FileInfo.from(fo));
                    } catch (FileSystemException e) {
                        // ignore unreadable files
                    }
                }
            }
        }
    }

    private void scanOnce() throws FileSystemException {
        // Set of files seen in this round
        Set<Path> seen = new HashSet<>();

        Deque<FileObject> stack = new ArrayDeque<>();
        stack.push(rootDir);

        while (!stack.isEmpty()) {
            FileObject dir = stack.pop();
            for (FileObject fo : safeChildren(dir)) {
                if (fo.isFolder()) {
                    stack.push(fo);
                    continue;
                }
                if (!fo.isFile()) continue;

                Path p = toLocalPath(fo);
                if (isExcluded(p)) continue;
                seen.add(p);

                FileInfo current;
                try {
                    try {
                        fo.refresh();
                    } catch (FileSystemException ignore) {
                    }
                    current = FileInfo.from(fo);
                } catch (FileSystemException ex) {
                    // Can't read -> skip this file in this cycle
                    continue;
                }

                FileInfo prev = knownFiles.put(p, current);
                if (prev == null) {
                    queue(FileEvent.created(p));
                } else if (!prev.equals(current)) {
                    queue(FileEvent.modified(p));
                }
            }
        }

        // Deletions: anything in knownFiles but not seen
        for (Path p : new ArrayList<>(knownFiles.keySet())) {
            if (isExcluded(p)) continue;
            if (!seen.contains(p)) {
                knownFiles.remove(p);
                queue(FileEvent.deleted(p));
            }
        }
    }

    private FileObject[] safeChildren(FileObject dir) {
        try {
            if (dir.exists() && dir.isFolder()) {
                try {
                    dir.refresh();
                } catch (FileSystemException ignore) {
                }
                FileObject[] kids = dir.getChildren();
                return (kids != null) ? kids : EMPTY;
            }
        } catch (FileSystemException ignore) {
        }
        return EMPTY;
    }

    private boolean isExcluded(Path path) {
        for (Path ex : excludedPaths) {
            if (path.startsWith(ex)) {
                return true;
            }
        }
        return false;
    }

    private void queue(FileEvent event) {
        final Path p = event.getPath();

        // Debounce check (per-path)
        long now = System.currentTimeMillis();
        long window = resolveDebounceWindow(p);
        Long last = lastEventTime.get(p);
        if (last != null && (now - last) < window) {
            return; // drop
        }
        lastEventTime.put(p, now);

        // Rate limit check
        if (globalRateLimiter != null && !globalRateLimiter.tryAcquire()) return;
        Limiter rl = resolveRateLimiter(p);
        if (rl != null && !rl.tryAcquire()) return;

        // Enqueue (drop if full to avoid blocking)
        eventQueue.offer(event);
    }

    private long resolveDebounceWindow(Path p) {
        Long v = debounceWindowPerPath.get(p);
        if (v != null) return v;
        for (Map.Entry<PathMatcher, Long> e : debounceWindowMatchers) {
            if (e.getKey().matches(p)) return e.getValue();
        }
        return debounceWindowMillis;
    }

    private Limiter resolveRateLimiter(Path p) {
        Limiter r = rateLimiterPerPath.get(p);
        if (r != null) return r;
        for (Map.Entry<PathMatcher, Limiter> e : rateLimiterMatchers) {
            if (e.getKey().matches(p)) return e.getValue();
        }
        return null;
    }

    private Path toLocalPath(FileObject fo) {
        // For "file" scheme this is an absolute path; for others it's used as identifier.
        String p = fo.getName().getPath();
        return java.nio.file.Paths.get(p);
    }

    /* ============ Builder ============ */

    public static class Builder {
        private FileSystemManager fsManager;
        private String rootUri;
        private DirectoryChangeListener listener;
        private long pollingIntervalMillis = 1000;
        private int maxQueueSize = 4096;

        private double rateLimitPerSecond = 0.0; // disabled by default
        private long debounceWindowMillis = 0L;  // disabled by default

        private final Set<Path> excludedPaths = new HashSet<>();
        private final Map<Path, Limiter> rateLimiterPerPath = new HashMap<>();
        private final List<Map.Entry<PathMatcher, Limiter>> rateLimiterMatchers = new ArrayList<>();
        private final Map<Path, Long> debounceWindowPerPath = new HashMap<>();
        private final List<Map.Entry<PathMatcher, Long>> debounceWindowMatchers = new ArrayList<>();

        private boolean shutdownHookEnabled = true;

        public Builder fsManager(FileSystemManager fsManager) {
            this.fsManager = fsManager;
            return this;
        }

        /**
         * Root URI, e.g. "file:///var/data" or "sftp://user@host/path".
         */
        public Builder rootUri(String rootUri) {
            this.rootUri = rootUri;
            return this;
        }

        /**
         * Convenience: set root by local filesystem Path (converted to file:// URI).
         */
        public Builder rootPath(java.nio.file.Path path) {
            Objects.requireNonNull(path, "path");
            this.rootUri = path.toUri().toString();
            return this;
        }

        /**
         * Convenience: set root by local filesystem path string (converted to file:// URI).
         */
        public Builder rootPath(String path) {
            Objects.requireNonNull(path, "path");
            java.nio.file.Path p = java.nio.file.Paths.get(path);
            this.rootUri = p.toUri().toString();
            return this;
        }

        public Builder listener(DirectoryChangeListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder pollingIntervalMillis(long interval) {
            if (interval <= 0) throw new IllegalArgumentException("interval must be > 0");
            this.pollingIntervalMillis = interval;
            return this;
        }

        public Builder maxQueueSize(int maxQueueSize) {
            if (maxQueueSize <= 0) throw new IllegalArgumentException("maxQueueSize must be > 0");
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        /**
         * Exclude a subtree from scanning/events. Prefix match via Path.startsWith().
         */
        public Builder excludePath(Path path) {
            if (path != null) this.excludedPaths.add(path);
            return this;
        }

        /**
         * Global rate limit (permits per second) across all events; 0 disables limiting.
         */
        public Builder globalRateLimitPerSecond(double permitsPerSecond) {
            if (permitsPerSecond < 0) throw new IllegalArgumentException("permitsPerSecond must be >= 0");
            this.rateLimitPerSecond = permitsPerSecond;
            return this;
        }

        /**
         * Per-path rate limit (permits per second).
         */
        public Builder perPathRateLimit(Path path, double permitsPerSecond) {
            if (path == null) throw new IllegalArgumentException("path is null");
            if (permitsPerSecond <= 0) throw new IllegalArgumentException("permitsPerSecond must be > 0");
            this.rateLimiterPerPath.put(path, new SimpleTokenBucketLimiter(permitsPerSecond));
            return this;
        }

        /**
         * Pattern-based rate limit. The syntax must include "glob:" or "regex:".
         * Example: matcherRateLimit("glob:
         **//*.log", 5.0)
         */
        public Builder matcherRateLimit(String syntaxAndPattern, double permitsPerSecond) {
            if (permitsPerSecond <= 0) throw new IllegalArgumentException("permitsPerSecond must be > 0");
            PathMatcher pm = FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
            this.rateLimiterMatchers.add(new AbstractMap.SimpleImmutableEntry<>(pm, new SimpleTokenBucketLimiter(permitsPerSecond)));
            return this;
        }

        /**
         * Global debounce window (ms). 0 disables debouncing.
         */
        public Builder globalDebounceWindowMillis(long millis) {
            if (millis < 0) throw new IllegalArgumentException("millis must be >= 0");
            this.debounceWindowMillis = millis;
            return this;
        }

        /**
         * Per-path debounce window (ms).
         */
        public Builder perPathDebounceWindow(Path path, long millis) {
            if (path == null) throw new IllegalArgumentException("path is null");
            if (millis < 0) throw new IllegalArgumentException("millis must be >= 0");
            this.debounceWindowPerPath.put(path, millis);
            return this;
        }

        /**
         * Pattern-based debounce window (ms). Syntax must include "glob:" or "regex:".
         * Example: matcherDebounceWindow("glob:
         **//*.tmp", 2000)
         */
        public Builder matcherDebounceWindow(String syntaxAndPattern, long millis) {
            if (millis < 0) throw new IllegalArgumentException("millis must be >= 0");
            PathMatcher pm = FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
            this.debounceWindowMatchers.add(new AbstractMap.SimpleImmutableEntry<>(pm, millis));
            return this;
        }

        /**
         * Install JVM shutdown hook that calls close(). Enabled by default.
         */
        public Builder shutdownHookEnabled(boolean enabled) {
            this.shutdownHookEnabled = enabled;
            return this;
        }

        public RecursiveVfsDirectoryWatcher build() throws FileSystemException {
            Objects.requireNonNull(rootUri, "rootUri");
            Objects.requireNonNull(listener, "listener");
            return new RecursiveVfsDirectoryWatcher(this);
        }
    }
}