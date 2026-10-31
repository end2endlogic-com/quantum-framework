package com.e2eq.framework.model.persistent.morphia;

/** Typed failure for governed MediaReference persistence. */
public class MediaReferenceOperationException extends IllegalArgumentException {

    public enum Code {
        CREATOR_REQUIRED,
        IMMUTABLE_CREATOR,
        IMMUTABLE_STORAGE_IDENTITY
    }

    private final Code code;

    public MediaReferenceOperationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
