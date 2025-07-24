package io.github.byzatic.commons;
public class ObjectsUtils {
    public static <T, E extends Throwable> void requireNonNull(T obj, E exception) throws E {
        if (obj == null) {
            throw exception;
        }
    }
}
