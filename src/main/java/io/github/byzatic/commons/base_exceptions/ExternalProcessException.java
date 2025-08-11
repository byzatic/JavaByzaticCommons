package io.github.byzatic.commons.base_exceptions;

public class ExternalProcessException extends Exception {
    public ExternalProcessException(String message) {
        super(message);
    }

    public ExternalProcessException(Throwable cause) {
        super(cause);
    }

    public ExternalProcessException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExternalProcessException(Throwable cause, String message) {
        super(message, cause);
    }
}
