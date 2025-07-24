package io.github.byzatic.commons;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public class TempDirectory {
    private final static Logger logger = LoggerFactory.getLogger(TempDirectory.class);
    final Path path;

    public TempDirectory(String prefix) {
        try {
            path = Files.createTempDirectory(prefix);
            logger.debug("Temp Directory created: {}", path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public TempDirectory(String prefix, Boolean deleteOnExitAutomatically) {
        try {
            path = Files.createTempDirectory(prefix);
            logger.debug("Temp Directory created: {}", path);
            if (deleteOnExitAutomatically) {
                deleteOnExit();
                logger.debug("Temp Directory {} delete on exit set", path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Path getPath() {
        return path;
    }

    public void deleteOnExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::delete));
    }

    public void delete() {
        deleteDirectoryRecursively();
    }

    public void deleteLegacy() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return super.postVisitDirectory(dir, exc);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return super.visitFile(file, attrs);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteDirectoryRecursively() {
        if (!Files.exists(path)) {
            return;
        }

        try {
            // Используем Files.walkFileTree для рекурсивного обхода файлов и директорий
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

                // Этот метод вызывается для каждого файла
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    logger.debug("File {} removed", file);
                    return FileVisitResult.CONTINUE;
                }

                // Этот метод вызывается после того, как все файлы в директории были посещены
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir); // Удаляем директорию после удаления всех её содержимого
                    logger.debug("Dir {} removed", dir);
                    return FileVisitResult.CONTINUE;
                }

                // В случае ошибки доступа или другого исключения, выводим сообщение
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    logger.error("Failed to delete {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteDirectoryContents() {
        if (!Files.exists(path)) {
            return;
        }

        try {
            // Используем Files.walkFileTree для рекурсивного обхода файлов и директорий
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

                // Этот метод вызывается для каждого файла
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); // Удаляем файл
                    return FileVisitResult.CONTINUE;
                }

                // Этот метод вызывается после того, как все файлы в поддиректории были посещены
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (!dir.equals(path)) { // Удаляем поддиректории, но не корневую директорию
                        Files.delete(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                // В случае ошибки доступа или другого исключения, выводим сообщение
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    logger.error("Failed to delete {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TempDirectory that = (TempDirectory) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return "TempDirectory{" +
                "path=" + path +
                '}';
    }
}
