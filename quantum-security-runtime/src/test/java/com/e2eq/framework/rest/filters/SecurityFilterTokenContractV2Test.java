package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalContextRequest;
import com.e2eq.framework.security.principal.PrincipalFixture;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token contract v2 user access tokens ({@code token_type=access}, identity claims only) on a
 * receiver that holds the credential store: they resolve in {@code IDENTITY} mode for this
 * application, exactly as Auth's resolve endpoint does, and a receiver that cannot do that refuses
 * them with a typed reason. Never the delegated-claims path.
 */
class SecurityFilterTokenContractV2Test {

    private static final Map<String, Object> V2 = Map.of(
            "iss", "https://auth.example.test", "sub", "sub-alice", "userId", "alice@acme",
            "jti", "8f6c", "scope", "authToken", DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM,
            DelegatedIdentityHeaders.ACCESS_TOKEN_TYPE);

    private final PrincipalFixture world = new PrincipalFixture();

    @Test
    void recognisesOnlyTheV2AccessTokenType() {
        assertTrue(filter(V2).identityOnlyAccessToken());
        assertFalse(filter(Map.of("sub", "sub-alice", "realm", "acme-D1")).identityOnlyAccessToken(),
                "a v1 user token carries no token_type");
        assertFalse(filter(Map.of("sub", "svc", DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM,
                DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE)).identityOnlyAccessToken());
    }

    @Test
    void resolvesForTheConfiguredApplicationFromTheCredential() {
        PrincipalContext context = filter(V2).resolveIdentityOnlyAccessToken(request(Map.of()), null, null).context();
        assertEquals("alice@acme", context.getUserId());
        assertEquals(PrincipalFixture.APP, context.getApplicationId());
        assertEquals("acme-D1", context.getDefaultRealm(), "the credential's home realm when no X-Realm");
        assertEquals("acme", context.getDataDomain().getTenantId());
    }

    @Test
    void xRealmSelectsAnAuthorizedRealm() {
        PrincipalContext context = filter(V2).resolveIdentityOnlyAccessToken(request(Map.of()), "acme-D2", null).context();
        assertEquals("acme-D2", context.getDefaultRealm());
        assertEquals("acme-two", context.getDataDomain().getTenantId());
    }

    @Test
    void aDisabledCredentialIsRefusedTyped() {
        world.credential("alice@acme").setActiveStatus(ActiveStatus.INACTIVE);
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter(V2).resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals(401, refused.status());
        assertEquals("PRINCIPAL_DISABLED", refused.code());
    }

    @Test
    void aSuspendedApplicationBindingIsRefused() {
        UserAppRole binding = world.bind("alice@acme", PrincipalFixture.APP, "acme-D1", List.of("acme"), "acme", "editor");
        binding.setStatus(UserAppRole.STATUS_SUSPENDED);
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter(V2).resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals(403, refused.status());
        assertEquals("APPLICATION_NOT_GRANTED", refused.code());
    }

    @Test
    void anApplicationTheIdentityIsNotGrantedIsRefused() {
        SecurityFilter filter = filter(V2);
        filter.sessionApplicationId = Optional.of("other-app");
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals("APPLICATION_NOT_GRANTED", refused.code());
    }

    @Test
    void xTenantIdIsCheckedAgainstTheAssignment() {
        PrincipalContext context = filter(V2).resolveIdentityOnlyAccessToken(
                request(Map.of("X-Tenant-Id", "tenant-b")), "pooled", null).context();
        assertEquals("tenant-b", context.getDataDomain().getTenantId());
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter(V2).resolveIdentityOnlyAccessToken(
                        request(Map.of("X-Tenant-Id", "tenant-z")), "pooled", null));
        assertEquals("TENANT_NOT_AUTHORIZED", refused.code());
    }

    @Test
    void impersonationIsAuthorizedByTheIdentityRules() {
        // alice has no impersonation rights; the header is not honoured from a v2 token either.
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter(V2).resolveIdentityOnlyAccessToken(
                        request(Map.of("X-Impersonate-UserId", "admin@acme")), null, null));
        assertEquals("IMPERSONATION_REFUSED", refused.code());
    }

    @Test
    void aReceiverWithoutAnApplicationIdRefusesTheV2Token() {
        SecurityFilter filter = filter(V2);
        filter.sessionApplicationId = Optional.empty();
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals(401, refused.status());
        assertEquals("TOKEN_CONTRACT_V2_UNRESOLVED", refused.code());
    }

    @Test
    void aClaimsTrustingReceiverRefusesTheV2TokenWithoutReadingAnyCredential() throws Exception {
        SecurityFilter filter = filter(V2);
        filter.trustTokenClaims = true;
        CredentialRepo repo = org.mockito.Mockito.mock(CredentialRepo.class);
        filter.credentialRepo = repo;
        assertTrue(localCredentialPath(filter), "never the delegated-claims path");
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals(401, refused.status());
        assertEquals("TOKEN_CONTRACT_V2_UNRESOLVED", refused.code());
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    @Test
    void aV1TokenKeepsTheSignedClaimsPathUntilStep4() throws Exception {
        SecurityFilter filter = filter(Map.of("sub", "sub-alice", "realm", "acme-D1", "tenantId", "acme-t"));
        Method method = SecurityFilter.class.getDeclaredMethod("localCredentialRequest",
                ContainerRequestContext.class, String.class, String.class, boolean.class);
        method.setAccessible(true);
        PrincipalContextRequest request;
        try {
            request = (PrincipalContextRequest) method.invoke(filter, request(Map.of()), null, null, false);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
        assertFalse(request.identityOnly());
        assertEquals("acme-D1", request.signedClaims().realm());
        assertEquals("acme-t", request.signedClaims().tenantId());
    }

    // ------------------------------------------------------------------ the identity authority (Auth)

    /** Auth: identity authority, no receiving application, its own application configured. */
    private SecurityFilter authority(Map<String, Object> claims, String ownApplication) {
        SecurityFilter filter = filter(claims);
        filter.sessionApplicationId = Optional.empty();
        filter.identityAuthority = true;
        com.e2eq.framework.model.persistent.morphia.ReceivingApplication receiving =
                org.mockito.Mockito.mock(com.e2eq.framework.model.persistent.morphia.ReceivingApplication.class);
        org.mockito.Mockito.when(receiving.applicationId()).thenReturn(null);
        org.mockito.Mockito.when(receiving.identityAuthorityApplicationId()).thenReturn(ownApplication);
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<com.e2eq.framework.model.persistent.morphia.ReceivingApplication> bean =
                org.mockito.Mockito.mock(jakarta.enterprise.inject.Instance.class);
        org.mockito.Mockito.when(bean.isResolvable()).thenReturn(true);
        org.mockito.Mockito.when(bean.get()).thenReturn(receiving);
        filter.receivingApplication = bean;
        return filter;
    }

    @Test
    void atTheIdentityAuthorityAV2TokenResolvesForAuthsOwnApplicationWithoutAdmission() {
        // alice holds no grant of Auth's own application: Auth's own operations admit any identity,
        // as they admit a v1 token signed in to another application. Roles come from the store.
        PrincipalContext context = authority(V2, "quantum-system")
                .resolveIdentityOnlyAccessToken(request(Map.of()), null, null).context();
        assertEquals("alice@acme", context.getUserId());
        assertEquals("quantum-system", context.getApplicationId(), "Auth's own application, never a token claim");
        assertEquals("acme-D1", context.getDefaultRealm(), "the credential's home realm when no X-Realm");
        assertEquals("acme", context.getDataDomain().getTenantId());
    }

    @Test
    void atTheIdentityAuthorityAnXRealmSwitchNeedsAuthsApplicationAdmittedThere() {
        // The realm-switch rule a v1 token signed in to Auth meets: the application (here Auth's
        // own) must be admitted in the target realm. acme-D2 admits only the fixture's application.
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> authority(V2, "quantum-system")
                .resolveIdentityOnlyAccessToken(request(Map.of()), "acme-D2", null));
        PrincipalContext admitted = authority(V2, PrincipalFixture.APP)
                .resolveIdentityOnlyAccessToken(request(Map.of()), "acme-D2", null).context();
        assertEquals("acme-D2", admitted.getDefaultRealm());
    }

    @Test
    void atTheIdentityAuthorityWithoutXRealmTheIdentitysDefaultRealmForAuthsApplicationApplies() {
        // As a v1 token signed in to Auth's application named that realm; the resolve operation's rule.
        world.bind("alice@acme", PrincipalFixture.APP, "acme-D2", List.of("acme-two"), "acme-two", "editor")
                .setDefaultRealm(Boolean.TRUE);
        PrincipalContext context = authority(V2, PrincipalFixture.APP)
                .resolveIdentityOnlyAccessToken(request(Map.of()), null, null).context();
        assertEquals("acme-D2", context.getDefaultRealm());
        assertEquals("acme-D1", authority(V2, PrincipalFixture.APP)
                .resolveIdentityOnlyAccessToken(request(Map.of()), "acme-D1", null).context().getDefaultRealm(),
                "X-Realm still selects");
    }

    @Test
    void atTheIdentityAuthorityTrustTokenClaimsDoesNotOpenTheClaimsPath() throws Exception {
        SecurityFilter filter = authority(V2, "quantum-system");
        filter.trustTokenClaims = true;
        assertTrue(localCredentialPath(filter));
        assertEquals("quantum-system",
                filter.resolveIdentityOnlyAccessToken(request(Map.of()), null, null).context().getApplicationId());
    }

    @Test
    void anIdentityAuthorityWithoutItsOwnApplicationRefusesTheV2Token() {
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> authority(V2, null).resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals(401, refused.status());
        assertEquals("TOKEN_CONTRACT_V2_UNRESOLVED", refused.code());
    }

    @Test
    void aReceiverThatIsNotTheAuthorityNeverTakesTheAuthorityApplication() {
        SecurityFilter filter = authority(V2, "quantum-system");
        filter.identityAuthority = false;
        assertEquals(null, filter.identityAuthorityApplicationId());
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.resolveIdentityOnlyAccessToken(request(Map.of()), null, null));
        assertEquals("TOKEN_CONTRACT_V2_UNRESOLVED", refused.code());
    }

    @Test
    void theRequestApplicationOfAV2TokenIsNeverItsAzp() {
        Map<String, Object> forged = new HashMap<>(V2);
        forged.put("azp", "forged-app");
        assertEquals("quantum-system", authority(forged, "quantum-system").currentApplicationId());
        SecurityFilter receiver = filter(forged);
        assertEquals(PrincipalFixture.APP, receiver.currentApplicationId());
        receiver.sessionApplicationId = Optional.empty();
        assertEquals(null, receiver.currentApplicationId(), "no application, not the token's azp");
    }

    // ------------------------------------------------------------------ fixtures

    private static boolean localCredentialPath(SecurityFilter filter) throws Exception {
        Method method = SecurityFilter.class.getDeclaredMethod("localCredentialPath", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(filter, "Bearer raw-token");
    }

    private SecurityFilter filter(Map<String, Object> claims) {
        SecurityFilter filter = new SecurityFilter();
        JsonWebToken token = jwt(claims);
        filter.jwt = token;
        filter.securityIdentity = QuarkusSecurityIdentity.builder().setPrincipal(token)
                .addRoles(Set.of("user")).build();
        filter.envConfigUtils = world.env;
        filter.systemDirectory = world.directory;
        filter.realmMembershipService = world.membership;
        filter.credentialRepo = world.credentialRepo;
        filter.identityRoleResolver = world.roleResolver;
        filter.securityUtils = world.securityUtils;
        @SuppressWarnings("unchecked")
        jakarta.enterprise.inject.Instance<com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory> directory =
                org.mockito.Mockito.mock(jakarta.enterprise.inject.Instance.class);
        org.mockito.Mockito.when(directory.isResolvable()).thenReturn(true);
        org.mockito.Mockito.when(directory.get()).thenReturn(world.access);
        filter.accessDirectory = directory;
        filter.sessionApplicationId = Optional.of(PrincipalFixture.APP);
        return filter;
    }

    private static JsonWebToken jwt(Map<String, Object> claims) {
        Map<String, Object> copy = new HashMap<>(claims);
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClaim" -> copy.get(String.valueOf(args[0]));
                    case "getName" -> copy.get("sub");
                    case "getGroups", "getClaimNames", "getAudience" -> Set.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "jwt";
                    default -> null;
                });
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return headers.get(String.valueOf(args[0]));
                    }
                    return method.getReturnType().equals(boolean.class) ? false : null;
                });
    }
}
