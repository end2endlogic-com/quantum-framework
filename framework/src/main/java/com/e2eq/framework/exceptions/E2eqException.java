package com.e2eq.framework.exceptions;

public class E2eqException extends Exception {
    public E2eqException() {
        super();
    }

    public E2eqException(String message) {
        super(message);
    }

    public E2eqException(String message, Throwable cause) {
        super(message, cause);
    }

    public E2eqException(Throwable cause) {
        super(cause);
    }

    public E2eqException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
