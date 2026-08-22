package com.e2eq.framework.rest.collaboration;

/** Typed REST-service failure for comment-chain operations. */
public class CommentOperationException extends RuntimeException {

    public enum Code {
        AUTHENTICATED_ACTOR_REQUIRED,
        INVALID_ID,
        INVALID_REQUEST,
        CHAIN_NOT_FOUND,
        COMMENT_NOT_FOUND,
        CHAIN_LOCKED
    }

    private final Code code;

    public CommentOperationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
