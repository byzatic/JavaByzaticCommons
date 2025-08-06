package io.github.byzatic.commons.base_exceptions;

public class OperationTimedOutException extends Exception {
    public OperationTimedOutException(String message) {
        super(message);
    }

    public OperationTimedOutException(Throwable cause) {
        super(cause);
    }

    public OperationTimedOutException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationTimedOutException(Throwable cause, String message) {
        super(message, cause);
    }
}
