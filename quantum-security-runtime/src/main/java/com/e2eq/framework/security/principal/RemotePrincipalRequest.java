package com.e2eq.framework.security.principal;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * What a receiving application asks Auth to resolve: the verified bearer, the identity it proves
 * ({@code iss}, {@code sub}), this application's id, and the request's realm, tenant,
 * impersonation and acting-on-behalf-of selections (the {@code X-Realm}, {@code X-Tenant-Id},
 * {@code X-Impersonate-*} and {@code X-Acting-On-Behalf-Of-*} headers). Nothing else from the
 * token is carried.
 *
 * @param bearer           the verified bearer token, presented to Auth as the caller's own
 * @param issuer           the verified token's {@code iss}
 * @param subject          the verified token's {@code sub}
 * @param applicationId    this (receiving) application's id; required
 * @param realm            the requested realm, or null for the identity's own
 * @param tenantId         the requested tenant, or null for the identity's default
 * @param impersonate      the identity to impersonate, or null
 * @param actingOnBehalfOf the identity a delegating service acts for, or null
 */
public record RemotePrincipalRequest(String bearer,
                                     String issuer,
                                     String subject,
                                     String applicationId,
                                     String realm,
                                     String tenantId,
                                     PrincipalRef impersonate,
                                     PrincipalRef actingOnBehalfOf) {

    public RemotePrincipalRequest {
        bearer = trimToNull(bearer);
        issuer = trimToNull(issuer);
        subject = trimToNull(subject);
        applicationId = trimToNull(applicationId);
        realm = trimToNull(realm);
        tenantId = trimToNull(tenantId);
        impersonate = selector(impersonate);
        actingOnBehalfOf = selector(actingOnBehalfOf);
        Objects.requireNonNull(bearer, "bearer is required");
        Objects.requireNonNull(applicationId, "applicationId is required");
    }

    /** Request headers naming the realm, tenant, impersonation and acting-on-behalf-of selections. */
    public static final String REALM_HEADER = "X-Realm";
    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String IMPERSONATE_SUBJECT_HEADER = "X-Impersonate-Subject";
    public static final String IMPERSONATE_USER_ID_HEADER = "X-Impersonate-UserId";
    public static final String ACTING_SUBJECT_HEADER =
            com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_SUBJECT;
    public static final String ACTING_USER_ID_HEADER =
            com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_USER_ID;

    /**
     * The request for a verified bearer from the request's headers. An identity named by both
     * userId and subject is refused here with Auth's own {@code INVALID_REQUEST}.
     */
    public static RemotePrincipalRequest fromHeaders(String bearer, String issuer, String subject,
                                                     String applicationId, UnaryOperator<String> header) {
        return new RemotePrincipalRequest(bearer, issuer, subject, applicationId,
                header.apply(REALM_HEADER),
                header.apply(TENANT_HEADER),
                namedOnce(header.apply(IMPERSONATE_USER_ID_HEADER), header.apply(IMPERSONATE_SUBJECT_HEADER)),
                namedOnce(header.apply(ACTING_USER_ID_HEADER), header.apply(ACTING_SUBJECT_HEADER)));
    }

    private static PrincipalRef namedOnce(String userId, String subject) {
        if (trimToNull(userId) != null && trimToNull(subject) != null) {
            throw new RemotePrincipalResolutionException(400, "INVALID_REQUEST",
                    "name an identity by userId or subject, not both",
                    "Both a userId and a subject header name the same selection");
        }
        return new PrincipalRef(userId, subject);
    }

    /**
     * The cache identity of this request: everything that decides the answer except the bearer
     * itself (two tokens for the same identity resolve alike).
     */
    public CacheKey cacheKey() {
        return new CacheKey(issuer, subject, applicationId, realm, tenantId, impersonate, actingOnBehalfOf);
    }

    /** Never prints the bearer. */
    @Override
    public String toString() {
        return "RemotePrincipalRequest[issuer=" + issuer + ", subject=" + subject
                + ", applicationId=" + applicationId + ", realm=" + realm + ", tenantId=" + tenantId
                + ", impersonate=" + impersonate + ", actingOnBehalfOf=" + actingOnBehalfOf + "]";
    }

    /** (iss, sub, application, realm, tenant, impersonation, acting-on-behalf-of). */
    public record CacheKey(String issuer, String subject, String applicationId, String realm, String tenantId,
                           PrincipalRef impersonate, PrincipalRef actingOnBehalfOf) {
    }

    private static PrincipalRef selector(PrincipalRef ref) {
        if (ref == null) {
            return null;
        }
        String userId = trimToNull(ref.userId());
        String subject = trimToNull(ref.subject());
        return userId == null && subject == null ? null : new PrincipalRef(userId, subject);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
