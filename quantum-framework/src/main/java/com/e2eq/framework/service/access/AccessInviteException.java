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
        /** Inviter tried to grant a role it does not hold in the invite's realm (403). */
        INVITE_ROLE_NOT_GRANTABLE,
        /** Inviter tried to grant an application it may not hand out in the invite's realm (403). */
        INVITE_APPLICATION_NOT_GRANTABLE,
        /**
         * The inviter cannot access the invite's realm: it is not in the inviter's authorizedRealms,
         * does not match its realmRegEx, or is the system realm and the inviter is not the system
         * user (403).
         */
        INVITE_REALM_NOT_ACCESSIBLE,
        /**
         * The existing account's realm access is a realmRegEx that does not include the invite's
         * realm; attaching it would replace the pattern and cut the account off from its other
         * realms, so an administrator must grant the realm instead (409).
         */
        INVITE_ACCOUNT_REALM_CONFLICT,
        /** An invite is for exactly one concrete realm; multi-realm or wildcard requests are refused (400). */
        INVITE_SINGLE_REALM_REQUIRED,
        /**
         * The inviter is not entitled to the partition the invite applies to (for example, in a
         * pooled realm its realm assignment does not authorize the tenant and it is not a realm
         * administrator), or the request claimed a scope other than the inviter's verified one (403).
         */
        INVITE_SCOPE_NOT_AUTHORIZED,
        /**
         * The inviter's data partition could not be resolved for the invite's realm, or the policy
         * cannot carry it to the invitee (for example it names an org the tenant's realm membership
         * does not, or it adds a policy filter). Nothing falls back to default fields (403).
         */
        INVITE_SCOPE_UNRESOLVABLE,
        /**
         * No usable acceptance policy: the configured or recorded policy id is unknown, a pooled
         * realm has no policy configured, the request named a policy other than the realm's, or
         * the policy does not serve this kind of realm (409).
         */
        INVITE_POLICY_UNAVAILABLE,
        /** Invite does not exist in this realm (404). */
        INVITE_NOT_FOUND,
        /** Provisioning failed after the token was verified; the invite stays pending (500). Details are logged only. */
        INVITE_PROVISIONING_FAILED,
        /**
         * A resend could not deliver the new token through the configured notification service.
         * Nothing was re-issued: the previously delivered token still works (502).
         */
        INVITE_NOTIFICATION_FAILED
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
