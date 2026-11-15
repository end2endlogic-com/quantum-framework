package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.ServiceKind;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service tokens v2 (IDENTITY_ONLY_TOKENS_DESIGN.md section 4) in an application that holds the
 * credential store. An identity-only service token ({@code token_type=service} with a {@code jti})
 * always resolves on the local credential path, never on a claims path (the service-claims path
 * would place it in the system realm), and is admitted fail closed: its active credential must be
 * here and record a kind, it never impersonates, and only a delegating credential acts for anyone.
 * Role-bearing service tokens (no {@code jti}) keep the old path until step 4.
 */
class SecurityFilterServiceTokenV2Test {

    private static final String BEARER = "Bearer x.y.z";
    private static final String SYSTEM_REALM = "system-com";

    @Test
    void anIdentityOnlyServiceTokenResolvesOnTheLocalCredentialPath() {
        assertTrue(filter(v2Token(), credential(ServiceKind.APPLICATION)).localCredentialPath(BEARER));
    }

    @Test
    void anIdentityOnlyServiceTokenNeverTakesAClaimsPathEvenWhenClaimsAreTrusted() {
        CredentialRepo repo = mock(CredentialRepo.class);
        SecurityFilter filter = filter(v2Token(), credential(ServiceKind.APPLICATION));
        filter.credentialRepo = repo;
        filter.trustTokenClaims = true;
        assertTrue(filter.localCredentialPath(BEARER));
        assertThrows(NotAuthorizedException.class, () -> filter.admitIdentityOnlyServiceToken(request(Map.of())));
        verifyNoInteractions(repo);
    }

    @Test
    void aRoleBearingServiceTokenKeepsTheServiceClaimsPath() {
        Map<String, Object> legacy = new HashMap<>(v2Token());
        legacy.remove("jti");
        assertFalse(filter(legacy, credential(ServiceKind.APPLICATION)).localCredentialPath(BEARER));
        // The signer's own random token id is not a credential id.
        legacy.put("jti", "0b6a3c55-random-token-id");
        assertFalse(filter(legacy, credential(ServiceKind.APPLICATION)).localCredentialPath(BEARER));
    }

    @Test
    void aUserTokenStaysOnTheLocalCredentialPathWithoutALookup() {
        CredentialRepo repo = mock(CredentialRepo.class);
        SecurityFilter filter = filter(Map.of("sub", "user-subject", "jti", "user-credential"), null);
        filter.credentialRepo = repo;
        assertTrue(filter.localCredentialPath(BEARER));
        verifyNoInteractions(repo);
    }

    @Test
    void noBearerIsNotTheLocalCredentialPath() {
        assertFalse(filter(v2Token(), credential(ServiceKind.APPLICATION)).localCredentialPath(null));
    }

    @Test
    void aMissingCredentialIsRefusedNotSentToTheSystemRealm() {
        assertThrows(NotAuthorizedException.class,
                () -> filter(v2Token(), null).admitIdentityOnlyServiceToken(request(Map.of())));
    }

    @Test
    void aCredentialWithoutAKindOrInactiveOrOtherwiseNamedIsRefused() {
        CredentialUserIdPassword kindless = credential(null);
        CredentialUserIdPassword inactive = credential(ServiceKind.APPLICATION);
        inactive.setActiveStatus(ActiveStatus.INACTIVE);
        CredentialUserIdPassword deleted = credential(ServiceKind.DELEGATING);
        deleted.setActiveStatus(ActiveStatus.DELETED);
        for (CredentialUserIdPassword stored : new CredentialUserIdPassword[]{kindless, inactive, deleted}) {
            assertThrows(NotAuthorizedException.class,
                    () -> filter(v2Token(), stored).admitIdentityOnlyServiceToken(request(Map.of())));
        }
        CredentialUserIdPassword otherSubject = credential(ServiceKind.APPLICATION);
        otherSubject.setSubject("another-credential");
        assertThrows(NotAuthorizedException.class, () -> filter(v2Token(), otherSubject)
                .admitIdentityOnlyServiceToken(request(Map.of())));
    }

    @Test
    void aServiceTokenNeverImpersonates() {
        for (ServiceKind kind : ServiceKind.values()) {
            for (String header : new String[]{"X-Impersonate-UserId", "X-Impersonate-Subject"}) {
                assertThrows(ForbiddenException.class, () -> filter(v2Token(), credential(kind))
                        .admitIdentityOnlyServiceToken(request(Map.of(header, "system-admin"))), kind + " " + header);
            }
        }
    }

    @Test
    void onlyADelegatingTokenActsOnBehalfOfAnIdentity() {
        for (String header : new String[]{DelegatedIdentityHeaders.EFFECTIVE_USER_ID,
                DelegatedIdentityHeaders.EFFECTIVE_SUBJECT}) {
            assertThrows(ForbiddenException.class, () -> filter(v2Token(), credential(ServiceKind.APPLICATION))
                    .admitIdentityOnlyServiceToken(request(Map.of(header, "someone"))), header);
            assertDoesNotThrow(() -> filter(v2Token(), credential(ServiceKind.DELEGATING))
                    .admitIdentityOnlyServiceToken(request(Map.of(header, "someone"))), header);
        }
        assertDoesNotThrow(() -> filter(v2Token(), credential(ServiceKind.APPLICATION))
                .admitIdentityOnlyServiceToken(request(Map.of())));
    }

    private static Map<String, Object> v2Token() {
        return Map.of("sub", "svc-subject", "userId", "svc-svc-subject", "jti", "svc-subject",
                DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE);
    }

    private static CredentialUserIdPassword credential(ServiceKind kind) {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setSubject("svc-subject");
        credential.setUserId("svc-svc-subject");
        credential.setServiceKind(kind);
        credential.setActiveStatus(ActiveStatus.ACTIVE);
        return credential;
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        headers.forEach((name, value) -> when(request.getHeaderString(name)).thenReturn(value));
        return request;
    }

    private static SecurityFilter filter(Map<String, Object> claims, CredentialUserIdPassword stored) {
        SecurityFilter filter = new SecurityFilter();
        JsonWebToken token = jwt(claims);
        filter.jwt = token;
        filter.securityIdentity = (io.quarkus.security.identity.SecurityIdentity) Proxy.newProxyInstance(
                io.quarkus.security.identity.SecurityIdentity.class.getClassLoader(),
                new Class<?>[]{io.quarkus.security.identity.SecurityIdentity.class},
                (proxy, method, args) -> {
                    if ("getPrincipal".equals(method.getName())) {
                        return token;
                    }
                    if ("getRoles".equals(method.getName())) {
                        return Set.of();
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn(SYSTEM_REALM);
        filter.envConfigUtils = env;
        CredentialRepo repo = mock(CredentialRepo.class);
        when(repo.findBySubject("svc-subject", SYSTEM_REALM, true)).thenReturn(Optional.ofNullable(stored));
        filter.credentialRepo = repo;
        return filter;
    }

    private static JsonWebToken jwt(Map<String, Object> claims) {
        Map<String, Object> copy = new HashMap<>(claims);
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> {
                    if ("getClaim".equals(method.getName())) {
                        return copy.get(String.valueOf(args[0]));
                    }
                    if ("getName".equals(method.getName()) || "getSubject".equals(method.getName())) {
                        return copy.get("sub");
                    }
                    if (method.getReturnType().equals(Set.class)) {
                        return Set.of();
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
    }
}
