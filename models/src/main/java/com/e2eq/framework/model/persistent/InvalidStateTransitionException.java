package com.e2eq.framework.model.persistent;

public class InvalidStateTransitionException extends Exception {
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
