package com.e2eq.framework.model.persistent.tasks;

/**
 * Typed, client-safe failure for a governed work-item operation.
 */
public class WorkItemOperationException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST,
        NOT_FOUND,
        NOT_ELIGIBLE,
        NOT_ASSIGNED,
        LEASE_EXPIRED,
        INVALID_TRANSITION,
        REVISION_CONFLICT,
        INTERNAL_ERROR
    }

    private final Code code;

    public WorkItemOperationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public WorkItemOperationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
