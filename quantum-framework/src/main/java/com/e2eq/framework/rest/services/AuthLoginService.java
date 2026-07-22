package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.rest.models.AccessibleRealmInfo;
import com.e2eq.framework.rest.models.AuthResponse;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class AuthLoginService {

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    RealmRepo realmRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    JWTParser jwtParser;

    /** Application-authorization signals a provider may return as a negative login response. */
    private static final String APP_SELECTION_REQUIRED = "ApplicationSelectionRequired";
    private static final String APP_NOT_AUTHORIZED = "ApplicationNotAuthorized";
    private static final String APP_AUTHORIZATION_MISSING = "ApplicationAuthorizationMissing";
    private static final String REALM_NOT_AUTHORIZED = "RealmNotAuthorized";
    private static final String REALM_AUTHORIZATION_UNAVAILABLE = "RealmAuthorizationUnavailable";

    public LoginResult login(String userId, String password) {
        return login(userId, password, null, null, null);
    }

    public LoginResult login(String userId, String password, String providerOverride) {
        return login(userId, password, providerOverride, null, null);
    }

    public LoginResult login(String userId, String password, String providerOverride, String applicationId) {
        return login(userId, password, providerOverride, applicationId, null);
    }

    public LoginResult login(String userId, String password, String providerOverride,
                             String applicationId, String realmId) {
        List<AuthProvider> authProviders = authProviderFactory.getLoginProviders(providerOverride);

        List<String> providerFailures = new ArrayList<>();
        for (AuthProvider authProvider : authProviders) {
            AuthProvider.LoginResponse loginResponse = withAnonymousAuthenticationAccess(() ->
                    dispatchLogin(authProvider, userId, password, applicationId, realmId));
            if (loginResponse.authenticated() && loginResponse.positiveResponse() != null) {
                Optional<CredentialUserIdPassword> credentialOp = withAnonymousAuthenticationAccess(() -> findCredential(
                        loginResponse.positiveResponse().identity() != null
                                && loginResponse.positiveResponse().identity().getPrincipal() != null
                                ? loginResponse.positiveResponse().identity().getPrincipal().getName()
                                : null,
                        loginResponse.positiveResponse().userId()));
                AuthResponse response = toAuthResponse(authProvider, loginResponse, credentialOp.orElse(null));
                return LoginResult.success(response);
            }
            // Application-authorization is a definitive decision for an authenticated user
            // (the password matched but the app is unauthorized / needs selecting); do not
            // fall through to other providers, which don't model app scoping.
            AuthProvider.LoginNegativeResponse negative = loginResponse.negativeResponse();
            if (negative != null && APP_SELECTION_REQUIRED.equals(negative.errorType())) {
                List<String> applications = (negative.errorDetails() == null || negative.errorDetails().isBlank())
                        ? List.of()
                        : List.of(negative.errorDetails().split(","));
                return LoginResult.needsApplicationSelection(applications, negative.errorMessage());
            }
            if (negative != null && APP_NOT_AUTHORIZED.equals(negative.errorType())) {
                return LoginResult.applicationDenied(negative.errorMessage());
            }
            if (negative != null && APP_AUTHORIZATION_MISSING.equals(negative.errorType())) {
                // Authenticated, but the credential has no application boundary and no
                // per-realm grant — a configuration defect, not bad credentials. Surface
                // the provider's diagnostic as a typed 422 instead of falling through to
                // other providers or a generic 401.
                return LoginResult.applicationAuthorizationMissing(negative.errorMessage());
            }
            if (negative != null && (REALM_NOT_AUTHORIZED.equals(negative.errorType())
                    || REALM_AUTHORIZATION_UNAVAILABLE.equals(negative.errorType()))) {
                return LoginResult.realmDenied(negative.errorMessage());
            }
            providerFailures.add(loginFailure(authProvider, loginResponse));
        }
        return LoginResult.failure(providerFailures);
    }

    static AuthProvider.LoginResponse dispatchLogin(AuthProvider provider, String userId,
                                                     String password, String applicationId,
                                                     String realmId) {
        return provider.login(userId, password, applicationId, realmId);
    }

    public Optional<CredentialUserIdPassword> findCredential(String subject, String userId) {
        String systemRealm = envConfigUtils.getSystemRealm();
        if (subject != null && !subject.isBlank()) {
            Optional<CredentialUserIdPassword> bySubject = credentialRepo.findBySubject(subject, systemRealm, true);
            if (bySubject.isPresent()) {
                return bySubject;
            }
        }
        if (userId != null && !userId.isBlank()) {
            Optional<CredentialUserIdPassword> byUserId = credentialRepo.findByUserId(userId, systemRealm, true);
            if (byUserId.isPresent()) {
                return byUserId;
            }
        }
        return Optional.empty();
    }

    public Optional<CredentialUserIdPassword> findLoginCredential(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        // Authentication semantics must not change when the credential store is
        // unavailable. A lookup failure is an operational error, not evidence that
        // the credential is absent and permission to try a different provider.
        return withAnonymousAuthenticationAccess(() -> credentialRepo.findByUserId(
                userId,
                envConfigUtils.getSystemRealm(),
                true));
    }

    /**
     * Refresh an authenticated session through the provider that issued the
     * refresh token. Provider refresh is authoritative because it re-resolves
     * the user's realm membership, roles, and application grant before minting
     * replacement tokens.
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return LoginResult.failure(List.of("refresh: refresh token is required"));
        }

        try {
            String tokenIssuer = jwtParser.parse(refreshToken).getIssuer();
            AuthProvider provider = authProviderFactory.getProviderForIssuer(tokenIssuer);
            AuthProvider.LoginResponse refreshResponse = withAnonymousAuthenticationAccess(() ->
                    provider.refreshTokens(refreshToken));
            if (refreshResponse != null
                    && refreshResponse.authenticated()
                    && refreshResponse.positiveResponse() != null) {
                AuthProvider.LoginPositiveResponse positive = refreshResponse.positiveResponse();
                Optional<CredentialUserIdPassword> credential = withAnonymousAuthenticationAccess(() -> findCredential(
                        positive.identity() != null && positive.identity().getPrincipal() != null
                                ? positive.identity().getPrincipal().getName()
                                : null,
                        positive.userId()));
                return LoginResult.success(toAuthResponse(provider, refreshResponse, credential.orElse(null)));
            }
        } catch (Exception ignored) {
            // Refresh failures are deliberately indistinguishable to clients.
            // Provider details and token material must not cross this boundary.
        }
        return LoginResult.failure(List.of("refresh: token validation or authorization failed"));
    }

    private <T> T withAnonymousAuthenticationAccess(Supplier<T> supplier) {
        var principal = SecurityCallScope.anonymous(
                envConfigUtils.getSystemRealm(),
                securityUtils.getSystemDataDomain(),
                "anonymous-authentication");
        var resource = SecurityCallScope.resource(
                principal,
                null,
                "SECURITY",
                "CREDENTIAL_USERID_PASSWORD",
                "authenticate");
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(principal, resource)) {
            return supplier.get();
        }
    }

    public List<AccessibleRealmInfo> resolveAccessibleRealms(CredentialUserIdPassword credential) {
        if (credential == null) {
            return List.of();
        }
        return realmRepo.computeAllowedRealms(credential).stream()
                .map(AccessibleRealmInfo::fromRealm)
                .toList();
    }

    private AuthResponse toAuthResponse(
            AuthProvider authProvider,
            AuthProvider.LoginResponse loginResponse,
            CredentialUserIdPassword credential) {
        AuthProvider.LoginPositiveResponse positive = loginResponse.positiveResponse();
        AuthResponse response = new AuthResponse();
        response.setAccess_token(positive.accessToken());
        response.setRefresh_token(positive.refreshToken());
        response.setExpires_at(positive.expirationTime());
        response.setMongodburl(positive.mongodbUrl());
        response.setRealm(positive.realm());
        response.setRoles(positive.roleAssignments().stream().map(RoleAssignment::toString).collect(Collectors.toList()));
        response.setAuthProvider(authProvider.getName());
        response.setAccessibleRealms(resolveAccessibleRealms(credential));
        // Application-scoped auth: surface the token's aud set + azp so the UI can
        // render an app switcher and know the active app (null when not configured).
        if (positive.audiences() != null) {
            response.setApplications(new ArrayList<>(positive.audiences()));
        }
        response.setActiveApplication(positive.activeApplication());
        return response;
    }

    private String loginFailure(AuthProvider authProvider, AuthProvider.LoginResponse loginResponse) {
        if (loginResponse != null && loginResponse.negativeResponse() != null) {
            return authProvider.getName() + ": " + loginResponse.negativeResponse().errorMessage();
        }
        return authProvider.getName() + ": Authentication failed";
    }

    public record LoginResult(AuthResponse response,
                              List<String> providerFailures,
                              int status,
                              List<String> applications,
                              String message) {
        public static LoginResult success(AuthResponse response) {
            return new LoginResult(response, List.of(), Response.Status.OK.getStatusCode(), null, null);
        }

        public static LoginResult failure(List<String> providerFailures) {
            return new LoginResult(null, List.copyOf(providerFailures),
                    Response.Status.UNAUTHORIZED.getStatusCode(), null, null);
        }

        /** Authenticated, but more than one application is authorized and none was selected. */
        public static LoginResult needsApplicationSelection(List<String> applications, String message) {
            return new LoginResult(null, List.of(), Response.Status.BAD_REQUEST.getStatusCode(),
                    List.copyOf(applications), message);
        }

        /** Authenticated, but the requested application is not authorized for this user/realm. */
        public static LoginResult applicationDenied(String message) {
            return new LoginResult(null, List.of(), Response.Status.FORBIDDEN.getStatusCode(), null, message);
        }

        /**
         * Authenticated, but the credential carries no application authorization at all
         * (no applicationRegEx, no per-realm grant) — application-scoped tokens cannot
         * be minted for it. 422: the request is well-formed; the account configuration
         * is not.
         */
        public static LoginResult applicationAuthorizationMissing(String message) {
            return new LoginResult(null, List.of(), 422, null, message);
        }

        public static LoginResult realmDenied(String message) {
            return new LoginResult(null, List.of(), Response.Status.FORBIDDEN.getStatusCode(), null, message);
        }

        public boolean authenticated() {
            return response != null;
        }

        public boolean needsApplicationSelection() {
            return status == Response.Status.BAD_REQUEST.getStatusCode();
        }

        public boolean applicationAuthorizationMissing() {
            return status == 422;
        }

        public Response toResponse() {
            if (authenticated()) {
                return Response.ok(response).build();
            }
            if (needsApplicationSelection()) {
                return Response.status(status)
                        .entity(java.util.Map.of(
                                "status", status,
                                "statusMessage", message != null ? message : "Application selection required",
                                "errorType", "ApplicationSelectionRequired",
                                "applications", applications != null ? applications : List.of()))
                        .build();
            }
            RestError error = RestError.builder()
                    .statusMessage(message != null ? message : "Authentication failed")
                    .reasonMessage(applicationAuthorizationMissing() ? APP_AUTHORIZATION_MISSING : null)
                    .debugMessage(String.join("; ", providerFailures))
                    .status(status)
                    .build();
            return Response.status(status).entity(error).build();
        }
    }
}
