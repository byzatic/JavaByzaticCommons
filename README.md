# JavaByzaticCommons

JavaByzaticCommons is a lightweight utility toolkit for JVM projects, offering small, focused modules including an Apache Commons VFS–based recursive directory watcher (with debouncing and rate limiting), JDBC URL parsing, validation helpers, temporary directory utilities, and an OperationIncompleteException for partial-failure workflows.

## Maven

Artifact available on Maven Central:  
https://central.sonatype.com/artifact/io.github.byzatic/java-byzatic-commons

```xml
<dependency>
    <groupId>io.github.byzatic</groupId>
    <artifactId>java-byzatic-commons</artifactId>
    <version>0.0.4</version>
</dependency>
```

## Included Components

### OperationIncompleteException

A custom checked exception that indicates partial failure of an operation. It extends `Exception` and provides several constructors for flexibility.

#### Constructors:
- `OperationIncompleteException(String message)`
- `OperationIncompleteException(Throwable cause)`
- `OperationIncompleteException(String message, Throwable cause)`
- `OperationIncompleteException(Throwable cause, String message)`

Useful when an operation completes partially, and you need to signal that not everything succeeded.

---

### JdbcUrlParser

A utility class for parsing JDBC URLs of the form:

```
jdbc:postgresql://host:port/database
```

#### Parsed Components:
- JDBC scheme (e.g. `jdbc`)
- Database type (e.g. `postgresql`)
- Host
- Port
- Database name

If the JDBC URL is malformed, the parser throws a `RuntimeException`.

#### Example:
```java
JdbcUrlParser parser = new JdbcUrlParser("jdbc:postgresql://localhost:5432/testdb");
System.out.println(parser.getHost());         // "localhost"
System.out.println(parser.getDatabaseName()); // "testdb"
```

---

### ObjectsUtils

A utility class for basic object validation.

#### Methods:
- `requireNonNull(obj, exception)` — throws the given exception if `obj` is null.

#### Example:
```java
ObjectsUtils.requireNonNull(value, new IllegalArgumentException("Value must not be null"));
```

---

### TempDirectory

A utility for creating and cleaning up temporary directories. It supports recursive deletion and optional automatic deletion on JVM shutdown.

#### Constructors:
- `TempDirectory(String prefix)`
- `TempDirectory(String prefix, Boolean deleteOnExitAutomatically)`

#### Methods:
- `getPath()` — returns the path to the temp directory.
- `deleteOnExit()` — schedules the directory for deletion at JVM shutdown.
- `delete()` — deletes the directory and its contents.
- `deleteLegacy()` — alternative recursive deletion method using `SimpleFileVisitor`.
- `deleteDirectoryRecursively()` — modern recursive deletion implementation.

#### Example:
```java
try (TempDirectory temp = new TempDirectory("mytemp", true)) {
Path path = temp.getPath();
// use the temp directory
}
// temp directory will be deleted on exit
```

---

### CustomConverter by [Konstantin Fedorov (SoulHunt3r)](https://github.com/SoulHunt3r)

A generic utility for converting objects from one class to another using reflection. It supports mapping fields by name or by a custom `@Reflectable` annotation.

#### Features:
- Recursively copies matching fields from source to destination.
- Supports lists and nested object conversion.
- Enum-to-enum conversion based on `name()`.
- Handles different types by recursively invoking conversion logic.

#### Annotation Support:
You can annotate fields with `@Reflectable(name = "customName")` to map fields between source and destination with different names.

#### Exceptions:
- Throws `DataConvertException` on reflection or instantiation errors.

#### Example:
```java
Destination dest = CustomConverter.parse(source, Destination.class);
```

#### Notes:
- Source and destination classes must have a no-argument constructor.
- For collections, element types must also be convertible.

## RecursiveVfsDirectoryWatcher

A lightweight, polling-based, **recursive** directory watcher built on Apache Commons VFS. It detects **file** events (`CREATED`, `MODIFIED`, `DELETED`) under a root directory (local or remote via VFS), with optional **rate limiting** and **debouncing** so you can tame event storms.

> Callbacks run on a single dedicated dispatcher thread. Keep handlers fast and non-blocking.

### Quick Start

```java
RecursiveVfsDirectoryWatcher watcher =
    new RecursiveVfsDirectoryWatcher.Builder()
        // Choose one:
        .rootPath(Paths.get("/var/data"))              // local path convenience
        // .rootPath("/var/data")                      // local path (string)
        // .rootUri("file:///var/data")                // explicit VFS URI
        // .rootUri("sftp://user@host:22/inbox/")      // remote via VFS (requires provider on classpath)
        .listener(new DirectoryChangeListener() {
            @Override public void onFileCreated(Path p)  { /* handle */ }
            @Override public void onFileModified(Path p) { /* handle */ }
            @Override public void onFileDeleted(Path p)  { /* handle */ }
            @Override public void onAny(Path p)          { /* auditing/metrics */ }
        })
        .pollingIntervalMillis(500)                     // faster reaction in tests/dev
        .globalDebounceWindowMillis(200)                // coalesce bursts per path
        .matcherDebounceWindow("glob:**/*.tmp", 2000)   // noisy tmp files: hold off
        .globalRateLimitPerSecond(200)                  // bound total throughput
        .matcherRateLimit("glob:**/*.log", 5)           // at most 5 events/sec for *.log
        .excludePath(Paths.get("/var/data/cache"))      // skip heavy subtree
        .shutdownHookEnabled(true)
        .build();

watcher.start();
// ...
watcher.close(); // or use watcher.stopWatching() for legacy API
```

### Typical Use‑Cases

- **Hot‑reload configuration**: watch `conf/` for new or changed YAML/JSON; debounce to avoid reload on partial writes.
- **Data dropbox ingestion** (local or SFTP): trigger pipeline when new files land; rate‑limit or debounce to prevent thundering herds.
- **Log shipping / metrics**: watch directories for rolling logs; treat rapid rotations with debounce; avoid overloading with rate limits.
- **Selective monitoring**: exclude `cache/` or `tmp/` subtrees; apply glob‑specific rules (e.g., separate policies for `**/*.csv` vs `**/*.tmp`).

### Event Semantics

- Detects file events by comparing `(size, lastModified)` snapshot per path between polling iterations.
- Emits **one `onAny(path)` per logical event** (after the specific callback).
- Directories are **not** reported as events; only files are tracked.
- First scan runs **immediately** on `start()`; subsequent scans run every `pollingIntervalMillis`.

### Builder Options (Parameters & Examples)

> Unless stated otherwise, values are optional and have sensible defaults. Options can be freely combined.

| Option | Type / Default | What it does | Example |
|---|---|---|---|
| `fsManager` | `FileSystemManager` / `null` | Supply a custom VFS manager (e.g., with extra providers). If `null`, uses `VFS.getManager()`. | `.fsManager(customManager)` |
| `rootUri` | `String` / **required** (unless using `rootPath(...)`) | Root as VFS URI. Must exist and be a directory. | `.rootUri("file:///var/data")`, `.rootUri("sftp://user@host/inbox")` |
| `rootPath(Path)` | `Path` | Convenience for local filesystem roots; converted to `file://` URI. | `.rootPath(Paths.get("/var/data"))` |
| `rootPath(String)` | `String` | Convenience for local filesystem roots; converted to `file://` URI. | `.rootPath("/var/data")` |
| `listener` | `DirectoryChangeListener` / **required** | Receives callbacks: `onFileCreated`, `onFileModified`, `onFileDeleted`, `onAny`. | `.listener(myListener)` |
| `pollingIntervalMillis` | `long` / `1000` | Period between scans. Lower values = faster detection, higher CPU/IO. | `.pollingIntervalMillis(250)` |
| `maxQueueSize` | `int` / `4096` | Bounded internal event queue size. When full, new events are dropped. | `.maxQueueSize(10_000)` |
| `excludePath` | `Path` | Exclude subtree(s) from scanning and events. Prefix match (`path.startsWith(excluded)`). | `.excludePath(Paths.get("/var/data/cache"))` |
| `globalRateLimitPerSecond` | `double` / `0` (off) | Global token bucket across **all** events. | `.globalRateLimitPerSecond(200)` |
| `perPathRateLimit` | `Path, double` | Rate limit for a specific path. | `.perPathRateLimit(Paths.get("/var/data/inbox/file.csv"), 1.0)` |
| `matcherRateLimit` | `String, double` | Rate limit by path pattern. The first arg must include the syntax prefix, typically `glob:`. | `.matcherRateLimit("glob:**/*.log", 5.0)` |
| `globalDebounceWindowMillis` | `long` / `0` (off) | Minimum silence between events **for the same path**. Drops events that arrive within the window. | `.globalDebounceWindowMillis(300)` |
| `perPathDebounceWindow` | `Path, long` | Debounce for a specific path. | `.perPathDebounceWindow(Paths.get("/var/data/inbox/big.csv"), 2_000)` |
| `matcherDebounceWindow` | `String, long` | Debounce by pattern (`glob:` or `regex:`). | `.matcherDebounceWindow("glob:**/*.tmp", 2_000)` |
| `shutdownHookEnabled` | `boolean` / `true` | If `true`, installs a JVM shutdown hook that calls `close()`. | `.shutdownHookEnabled(false)` |

**Notes on patterns:** For `matcherRateLimit` and `matcherDebounceWindow`, pass the full syntax, e.g. `"glob:**/*.csv"` or `"regex:.*\\.csv"`. Patterns are evaluated with `FileSystems.getDefault().getPathMatcher(...)` against the watcher's `Path` representation.

### Threading & Performance

- One daemon thread performs polling; one daemon thread dispatches callbacks.
- Avoid blocking in callbacks; offload heavy work to your executor.
- On large trees, prefer higher `pollingIntervalMillis`, exclude heavy subtrees, and enable debounce/rate‑limits.

### Backward Compatibility

- `startWatching()` and `stopWatching()` are available as aliases for `start()` and `close()`.
- `rootPath(...)` overloads complement `rootUri(...)` to ease local usage.

### Limitations

- Polling can miss very short‑lived create/delete between scans; adjust interval and debounce to your needs.
- Change detection is based on `(size, lastModified)`; exotic filesystems that do not update these might not be tracked accurately.
- Pattern matching uses the default JVM `FileSystem` semantics, which may differ from remote VFS providers' native path rules.

---

## Token-bucket limiter (Limiter, SimpleTokenBucketLimiter)

A tiny, non-blocking rate-limiting module:
- `Limiter` — `boolean tryAcquire()` contract.
- `SimpleTokenBucketLimiter` — thread-safe token bucket:
    - rate: `ratePerSecond`,
    - burst capacity: `max(1, ratePerSecond)`,
    - O(1) per call, no external deps.

**Standalone example:**
```java
Limiter apiLimiter = new SimpleTokenBucketLimiter(20.0); // ~20 rps
if (apiLimiter.tryAcquire()) {
    callExternalApi();
} else {
    // skip or reschedule
}
```

**With the watcher:**
```java
new RecursiveVfsDirectoryWatcher.Builder()
    .rootPath(Paths.get("/data"))
    .listener(new DirectoryChangeListener() { /* ... */ })
    .matcherRateLimit("glob:**/*.csv", 2.0)
    .perPathRateLimit(Paths.get("/data/inbox/heavy.csv"), 0.2)
    .build();
```

---

## Requirements

- Java 17 or higher

## License

Apache License 2.0  
https://www.apache.org/licenses/LICENSE-2.0

## Author

Svyatoslav Vlasov  
https://github.com/byzatic
