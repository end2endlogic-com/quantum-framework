package com.e2eq.framework.security.principal;

import io.quarkus.security.identity.SecurityIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link PrincipalContextResolver#resolve}: who the verified token identifies, the
 * receiving application, and what the request asks for (realm, tenant, impersonation,
 * acting on behalf of).
 *
 * <p>{@link Mode#IDENTITY} is the identity-only contract of
 * {@code IDENTITY_ONLY_TOKENS_DESIGN.md}: nothing but the identity is read from the token,
 * and refusals are typed {@link PrincipalResolutionException}s.
 *
 * <p>{@link Mode#SIGNED_TOKEN_CLAIMS} is {@code SecurityFilter}'s local credential path as it
 * is today: it also honours the signed token's realm, tenant and role claims
 * ({@link SignedClaims}) and fails with the exceptions that path always threw. It exists so the
 * filter and the resolution endpoint share one implementation until token claims are retired
 * (rollout step 4).
 */
public record PrincipalContextRequest(Mode mode,
                                      String subject,
                                      List<String> userIdCandidates,
                                      String applicationId,
                                      String realm,
                                      String tenantId,
                                      String impersonateSubject,
                                      String impersonateUserId,
                                      String actingOnBehalfOfSubject,
                                      String actingOnBehalfOfUserId,
                                      SignedClaims signedClaims,
                                      boolean tenantSelectionOptional) {

    /** The 1.4.3 step-1 shape: tenant selection required. */
    public PrincipalContextRequest(Mode mode, String subject, List<String> userIdCandidates, String applicationId,
                                   String realm, String tenantId, String impersonateSubject,
                                   String impersonateUserId, String actingOnBehalfOfSubject,
                                   String actingOnBehalfOfUserId, SignedClaims signedClaims) {
        this(mode, subject, userIdCandidates, applicationId, realm, tenantId, impersonateSubject,
                impersonateUserId, actingOnBehalfOfSubject, actingOnBehalfOfUserId, signedClaims, false);
    }

    public enum Mode {
        /** Identity-only resolution with typed refusals. */
        IDENTITY,
        /** SecurityFilter's local credential path, honouring signed token claims. */
        SIGNED_TOKEN_CLAIMS
    }

    /**
     * What {@code SecurityFilter} reads from the signed token and the request today, beyond the
     * identity. Used only in {@link Mode#SIGNED_TOKEN_CLAIMS}.
     *
     * @param realm                   the token's {@code realm} claim (the realm the base context
     *                                is built in; null = the credential's home realm)
     * @param tenantId                the token's {@code tenantId} claim, applied in a shared realm
     *                                while the request stays in {@code realm}
     * @param rolesIdentity           the security identity whose token roles count, or null when
     *                                they must not (a token minted for another application)
     * @param foreignApplicationToken the token was minted for another application than the
     *                                configured session application
     * @param impersonationRealm      the raw {@code X-Realm} header, the realm an impersonation is
     *                                checked against
     * @param originalSubject         the {@code X-Originally-Authenticated-Subject} header
     * @param originalUserId          the {@code X-Originally-Authenticated-UserId} header
     * @param tenantSelectionExempt   the endpoint is a platform operation that needs no tenant
     */
    public record SignedClaims(String realm,
                               String tenantId,
                               SecurityIdentity rolesIdentity,
                               boolean foreignApplicationToken,
                               String impersonationRealm,
                               String originalSubject,
                               String originalUserId,
                               boolean tenantSelectionExempt) {
    }

    public PrincipalContextRequest {
        Objects.requireNonNull(mode, "mode");
        userIdCandidates = userIdCandidates == null ? List.of() : List.copyOf(userIdCandidates);
        if (mode == Mode.SIGNED_TOKEN_CLAIMS && signedClaims == null) {
            throw new IllegalArgumentException("SIGNED_TOKEN_CLAIMS resolution requires signedClaims");
        }
        if (mode == Mode.IDENTITY && signedClaims != null) {
            throw new IllegalArgumentException("IDENTITY resolution reads no token claims beyond the identity");
        }
    }

    public boolean identityOnly() {
        return mode == Mode.IDENTITY;
    }

    public boolean impersonationRequested() {
        return impersonateSubject != null || impersonateUserId != null;
    }

    public boolean delegationRequested() {
        return nonBlank(actingOnBehalfOfSubject) || nonBlank(actingOnBehalfOfUserId);
    }

    static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static Builder identity(String subject, String userId) {
        Builder builder = new Builder(Mode.IDENTITY).subject(subject);
        if (nonBlank(userId)) {
            builder.userIdCandidate(userId.trim());
        }
        return builder;
    }

    public static Builder signedTokenClaims(String subject, SignedClaims claims) {
        return new Builder(Mode.SIGNED_TOKEN_CLAIMS).subject(subject).signedClaims(claims);
    }

    public static final class Builder {
        private final Mode mode;
        private String subject;
        private final List<String> userIdCandidates = new ArrayList<>();
        private String applicationId;
        private String realm;
        private boolean tenantSelectionOptional;
        private String tenantId;
        private String impersonateSubject;
        private String impersonateUserId;
        private String actingOnBehalfOfSubject;
        private String actingOnBehalfOfUserId;
        private SignedClaims signedClaims;

        private Builder(Mode mode) {
            this.mode = mode;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder userIdCandidate(String userId) {
            if (userId != null && !userIdCandidates.contains(userId)) {
                userIdCandidates.add(userId);
            }
            return this;
        }

        public Builder applicationId(String applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder realm(String realm) {
            this.realm = realm;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder impersonate(String subject, String userId) {
            this.impersonateSubject = subject;
            this.impersonateUserId = userId;
            return this;
        }

        public Builder actingOnBehalfOf(String subject, String userId) {
            this.actingOnBehalfOfSubject = subject;
            this.actingOnBehalfOfUserId = userId;
            return this;
        }

        /**
         * Identity mode: the request needs no tenant (a platform operation). With no tenant
         * requested, the principal keeps its base data domain instead of failing
         * {@code TENANT_SELECTION_REQUIRED}; a requested tenant is still authorized. The signed
         * path's equivalent is {@link SignedClaims#tenantSelectionExempt()}.
         */
        public Builder tenantSelectionOptional(boolean optional) {
            this.tenantSelectionOptional = optional;
            return this;
        }

        Builder signedClaims(SignedClaims claims) {
            this.signedClaims = claims;
            return this;
        }

        public PrincipalContextRequest build() {
            return new PrincipalContextRequest(mode, subject, userIdCandidates, applicationId, realm, tenantId,
                    impersonateSubject, impersonateUserId, actingOnBehalfOfSubject, actingOnBehalfOfUserId,
                    signedClaims, tenantSelectionOptional);
        }
    }
}
