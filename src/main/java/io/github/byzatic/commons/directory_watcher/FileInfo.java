package io.github.byzatic.commons.directory_watcher;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;

import java.util.Objects;

/**
 * Snapshot of file state used to detect changes.
 */
final class FileInfo {
    private final long size;
    private final long lastModified;

    FileInfo(long size, long lastModified) {
        this.size = size;
        this.lastModified = lastModified;
    }

    long getSize() {
        return size;
    }

    long getLastModified() {
        return lastModified;
    }

    static FileInfo from(FileObject fo) throws FileSystemException {
        long sz = -1L;
        long lm = -1L;
        if (fo != null && fo.exists() && fo.isFile()) {
            sz = fo.getContent().getSize();
            lm = fo.getContent().getLastModifiedTime();
        }
        return new FileInfo(sz, lm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileInfo)) return false;
        FileInfo fileInfo = (FileInfo) o;
        return size == fileInfo.size && lastModified == fileInfo.lastModified;
    }

    @Override
    public int hashCode() {
        return Objects.hash(size, lastModified);
    }

    @Override
    public String toString() {
        return "FileInfo{" +
                "size=" + size +
                ", lastModified=" + lastModified +
                '}';
    }
}
