package com.e2eq.framework.model.securityrules;

/**
 * HTTP contract for a trusted service performing work for an application user.
 *
 * <p>The bearer token authenticates the calling service. These headers identify
 * the effective user and, only during sudo/impersonation, the user who originally
 * authenticated. Receivers must reject these headers unless the bearer is a
 * service token issued by the trusted platform signer.
 */
public final class DelegatedIdentityHeaders {

    public static final String EFFECTIVE_SUBJECT = "X-Acting-On-Behalf-Of-Subject";
    public static final String EFFECTIVE_USER_ID = "X-Acting-On-Behalf-Of-UserId";
    public static final String ORIGINAL_SUBJECT = "X-Originally-Authenticated-Subject";
    public static final String ORIGINAL_USER_ID = "X-Originally-Authenticated-UserId";

    public static final String TOKEN_TYPE_CLAIM = "token_type";
    public static final String SERVICE_TOKEN_TYPE = "service";
    /**
     * {@code token_type} of an identity-only user access token (token contract v2,
     * {@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 1). A v1 user token carries no
     * {@code token_type}.
     */
    public static final String ACCESS_TOKEN_TYPE = "access";

    private DelegatedIdentityHeaders() {
    }

    /**
     * True for the {@code token_type} claim value of a token contract v2 user access token. Only
     * such a token falls back to the identity authority's own application: a v1 token (role-bearing,
     * service or user) and a v2 service token keep the application they resolved for.
     */
    public static boolean isAccessTokenType(Object tokenTypeClaim) {
        return tokenTypeClaim != null && ACCESS_TOKEN_TYPE.equalsIgnoreCase(String.valueOf(tokenTypeClaim).trim());
    }
}
