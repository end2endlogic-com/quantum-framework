package com.e2eq.framework.rest.media;

/** Typed, fail-closed storage/signing failure. */
public class MediaStorageException extends RuntimeException {

    public enum Code {
        STORAGE_UNAVAILABLE,
        INVALID_REFERENCE,
        OBJECT_NOT_FOUND,
        ACCESS_DENIED,
        SIGNING_FAILED,
        DELETE_FAILED
    }

    private final Code code;

    public MediaStorageException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public MediaStorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
