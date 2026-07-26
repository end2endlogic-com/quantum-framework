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

    private DelegatedIdentityHeaders() {
    }
}
