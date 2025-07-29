# JavaByzaticCommons

JavaByzaticCommons is a lightweight Java library that provides a set of reusable utilities for common development needs, including JDBC URL parsing, object validation, temporary directory handling, and custom exceptions for partial failures.

## Maven

Artifact available on Maven Central:  
https://central.sonatype.com/artifact/io.github.byzatic/java-byzatic-commons/overview

```xml
<dependency>
    <groupId>io.github.byzatic</groupId>
    <artifactId>java-byzatic-commons</artifactId>
    <version>0.0.1</version>
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

### CustomConverter

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

---

## Requirements

- Java 8 or higher

## License

Apache License 2.0  
https://www.apache.org/licenses/LICENSE-2.0

## Author

Svyatoslav Vlasov  
https://github.com/byzatic
