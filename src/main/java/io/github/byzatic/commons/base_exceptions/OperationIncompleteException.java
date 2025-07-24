package io.github.byzatic.commons.base_exceptions;

public class OperationIncompleteException extends Exception {

    public OperationIncompleteException(String message) {
        super(message);
    }

    public OperationIncompleteException(Throwable cause) {
        super(cause);
    }

    public OperationIncompleteException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationIncompleteException(Throwable cause, String message) {
        super(message, cause);
    }

}
