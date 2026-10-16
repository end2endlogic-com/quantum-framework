package com.e2eq.framework.service.access;

/**
 * Typed, client-safe refusal for an access-invite operation. Each code maps to a fixed HTTP
 * status in {@code AccessInviteExceptionMapper}; messages never contain tokens or hashes.
 */
public class AccessInviteException extends RuntimeException {

    public enum Code {
        /** Request is malformed or missing a required value (400). */
        INVALID_REQUEST,
        /** Token is missing, unknown, or tampered with (400). */
        INVITE_TOKEN_INVALID,
        /** Invite expired before it was accepted (410). */
        INVITE_EXPIRED,
        /** Invite was already accepted or revoked; tokens are single-use (409). */
        INVITE_NOT_PENDING,
        /** A body userId/email differs from the identity bound to the invite (403). */
        INVITE_IDENTITY_MISMATCH,
        /** The invited identity already has an account; the caller must sign in as it first (401). */
        INVITE_SIGN_IN_REQUIRED,
        /** Caller is neither the inviter nor an administrator of the invite's realm (403). */
        INVITE_NOT_AUTHORIZED,
        /** Inviter tried to grant a role it does not hold (403). */
        INVITE_ROLE_NOT_GRANTABLE,
        /** Invite does not exist in this realm (404). */
        INVITE_NOT_FOUND,
        /** Provisioning failed after the token was verified; the invite stays pending (500). Details are logged only. */
        INVITE_PROVISIONING_FAILED
    }

    private final Code code;

    public AccessInviteException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
