package com.e2eq.framework.model.persistent.morphia;

/** Typed failure raised when a comment would violate append-only hierarchy rules. */
public class CommentHierarchyException extends IllegalArgumentException {

    public enum Code {
        CHAIN_REQUIRED,
        CHAIN_NOT_FOUND,
        PARENT_NOT_FOUND,
        PARENT_CHAIN_MISMATCH,
        MAX_DEPTH_EXCEEDED,
        IMMUTABLE_HIERARCHY,
        IMMUTABLE_AUTHOR,
        IMMUTABLE_REQUEST_ID,
        MEDIA_REFERENCE_INVALID,
        MEDIA_REFERENCE_NOT_FOUND
    }

    private final Code code;

    public CommentHierarchyException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
