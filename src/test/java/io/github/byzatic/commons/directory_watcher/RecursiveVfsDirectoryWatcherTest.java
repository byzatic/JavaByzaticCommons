package io.github.byzatic.commons.directory_watcher;

import org.apache.commons.vfs2.FileSystemException;
import org.junit.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class RecursiveVfsDirectoryWatcherTest {

    private static Path root;
    private RecursiveVfsDirectoryWatcher watcher;
    private TestListener listener;

    @BeforeClass
    public static void setupClass() throws Exception {
        root = Files.createTempDirectory("watcher_test");
    }

    @AfterClass
    public static void cleanupClass() throws IOException {
        if (root != null) {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
        }
    }

    @After
    public void stopWatcher() throws IOException {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    public void testFileCreateModifyDelete() throws Exception {
        listener = new TestListener();
        watcher = new RecursiveVfsDirectoryWatcher.Builder()
                .rootPath(root)
                .listener(listener)
                .pollingIntervalMillis(250)
                .build();
        watcher.start();

        Path file = root.resolve("file.txt");
        Files.writeString(file, "hello");

        waitUntil(() -> listener.created.contains(file), Duration.ofSeconds(5));
        waitUntil(() -> listener.any.contains(file), Duration.ofSeconds(5));

        Files.writeString(file, "updated");
        waitUntil(() -> listener.modified.contains(file), Duration.ofSeconds(5));

        Files.delete(file);
        waitUntil(() -> listener.deleted.contains(file), Duration.ofSeconds(5));
    }

    @Test
    public void testDeepFileDetected() throws Exception {
        listener = new TestListener();
        watcher = new RecursiveVfsDirectoryWatcher.Builder()
                .rootPath(root)
                .listener(listener)
                .pollingIntervalMillis(250)
                .build();
        watcher.start();

        Path subdir = root.resolve("a/b/c");
        Files.createDirectories(subdir);
        Path file = subdir.resolve("deep.txt");
        Files.writeString(file, "data");

        waitUntil(() -> listener.created.contains(file), Duration.ofSeconds(5));
        waitUntil(() -> listener.any.contains(file), Duration.ofSeconds(5));

        // The watcher emits events for files, not for ancestor directories.
        assertFalse("Ancestor directory unexpectedly present in 'any' list", listener.any.contains(subdir));
        assertFalse("Root unexpectedly present in 'any' list", listener.any.contains(root));
    }

    @Test
    public void testExcludedPaths() throws Exception {
        Path excludedDir = root.resolve("excluded");
        Files.createDirectories(excludedDir);
        Path other = root.resolve("included");
        Files.createDirectories(other);

        listener = new TestListener();
        watcher = new RecursiveVfsDirectoryWatcher.Builder()
                .rootPath(root)
                .listener(listener)
                .excludePath(excludedDir)
                .pollingIntervalMillis(250)
                .build();
        watcher.start();

        Path ok = other.resolve("good.txt");
        Files.writeString(ok, "ok");
        Path bad = excludedDir.resolve("bad.txt");
        Files.writeString(bad, "no");

        waitUntil(() -> listener.any.contains(ok), Duration.ofSeconds(5));
        // Ensure that no events from excluded subtree are observed
        assertFalse("Excluded path leaked into events", listener.any.stream().anyMatch(p -> p.startsWith(excludedDir)));
    }

    @Test
    public void testRateLimiterPerPath() throws Exception {
        listener = new TestListener();
        watcher = new RecursiveVfsDirectoryWatcher.Builder()
                .rootPath(root)
                .listener(listener)
                .globalRateLimitPerSecond(100)
                .matcherRateLimit("glob:**/limited.txt", 0.1) // one event per ~10s
                .pollingIntervalMillis(250)
                .build();
        watcher.start();

        Path limited = root.resolve("limited.txt");
        for (int i = 0; i < 5; i++) {
            Files.writeString(limited, "data " + i);
            Thread.sleep(200);
        }

        // Give dispatcher time to process
        Thread.sleep(1500);
        long matches = listener.any.stream().filter(p -> p.endsWith("limited.txt")).count();
        assertTrue("Rate limiter per path not working as expected: got " + matches, matches <= 2);
    }

    @Test
    public void testDebouncePerPath() throws Exception {
        listener = new TestListener();
        watcher = new RecursiveVfsDirectoryWatcher.Builder()
                .rootPath(root)
                .listener(listener)
                .globalDebounceWindowMillis(100)
                .matcherDebounceWindow("glob:**/bounced.txt", 2000)
                .pollingIntervalMillis(250)
                .build();
        watcher.start();

        Path debounced = root.resolve("bounced.txt");
        Files.writeString(debounced, "one");
        Thread.sleep(300);
        Files.writeString(debounced, "two");
        Thread.sleep(300);
        Files.writeString(debounced, "three");

        // After enough time, only the first event should have passed for this path (due to 2s debounce)
        Thread.sleep(2500);
        long triggered = listener.any.stream().filter(p -> p.endsWith("bounced.txt")).count();
        assertTrue("Debounce per path failed: got " + triggered, triggered <= 1);
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long end = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Condition not met within " + timeout.toMillis() + " ms");
    }

    static class TestListener implements DirectoryChangeListener {
        List<Path> created = new CopyOnWriteArrayList<>();
        List<Path> modified = new CopyOnWriteArrayList<>();
        List<Path> deleted = new CopyOnWriteArrayList<>();
        List<Path> any = new CopyOnWriteArrayList<>();

        @Override
        public void onFileCreated(Path path) {
            created.add(path);
        }

        @Override
        public void onFileModified(Path path) {
            modified.add(path);
        }

        @Override
        public void onFileDeleted(Path path) {
            deleted.add(path);
        }

        @Override
        public void onAny(Path path) {
            any.add(path);
        }
    }
}
