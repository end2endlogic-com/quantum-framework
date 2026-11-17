package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalContextRequest;
import com.e2eq.framework.security.principal.PrincipalContextResolution;
import com.e2eq.framework.security.principal.PrincipalFixture;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory;
import com.e2eq.framework.model.persistent.morphia.ReceivingApplication;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service tokens v2 resolved end to end on the embedded/local credential path
 * ({@link SecurityFilter#determinePrincipalContext}): at a receiver that names its application the
 * request is an identity-mode request, so application admission, realm and tenant selection and
 * the SERVICE scope are the ones Auth's resolve operation returns for the same identity; a
 * DELEGATING token acts on behalf of an identity only through the same validated resolution.
 */
class SecurityFilterServiceTokenV2ResolutionTest {

    private final PrincipalFixture world = new PrincipalFixture();

    @Test
    void anApplicationTokenResolvesInIdentityModeAsAuthResolvesIt() {
        PrincipalContext viaFilter = viaFilter("svc-app@acme", PrincipalFixture.APP, Map.of());
        PrincipalContext viaAuth = world.resolver().resolve(world.identity("svc-app@acme").build()).context();

        assertEquals(PrincipalContext.SCOPE_SERVICE, viaFilter.getScope());
        assertEquals(PrincipalFixture.APP, viaFilter.getApplicationId());
        assertEquals(viaAuth.getDefaultRealm(), viaFilter.getDefaultRealm());
        assertEquals(viaAuth.getDataDomain(), viaFilter.getDataDomain());
        assertEquals(viaAuth.getDomainContext(), viaFilter.getDomainContext());
        assertArrayEquals(viaAuth.getRoles(), viaFilter.getRoles());
        assertEquals("acme-D1", viaFilter.getDefaultRealm());
        assertEquals("acme", viaFilter.getDataDomain().getTenantId());
        assertNull(viaFilter.getActingOnBehalfOfUserId());
    }

    @Test
    void aTokenForAServiceNotGrantedTheReceivingApplicationIsRefused() {
        // The signed-claims request skipped application admission for a service token; identity
        // mode refuses an application the credential holds no grant for.
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> viaFilter("svc-app@acme", "another-application", Map.of()));
        assertEquals(403, refused.status());
        assertEquals("APPLICATION_NOT_GRANTED", refused.code());
    }

    @Test
    void anApplicationBindingDecidesTheServicesRolesAndTenant() {
        world.declare(PrincipalFixture.APP, "orders-reader");
        world.install(PrincipalFixture.APP, "acme-D1", "acme");
        world.assign("svc-app@acme", "acme-D1", List.of());
        world.bind("svc-app@acme", PrincipalFixture.APP, "acme-D1", List.of("acme"), "acme", "orders-reader");

        PrincipalContext viaFilter = viaFilter("svc-app@acme", PrincipalFixture.APP, Map.of());
        PrincipalContext viaAuth = world.resolver().resolve(world.identity("svc-app@acme").build()).context();

        assertArrayEquals(viaAuth.getRoles(), viaFilter.getRoles());
        assertTrue(List.of(viaFilter.getRoles()).contains("orders-reader"), List.of(viaFilter.getRoles()).toString());
        assertEquals(viaAuth.getDataDomain(), viaFilter.getDataDomain());
    }

    @Test
    void aDelegatingTokenActsForTheIdentityThroughTheValidatedResolvePath() {
        // The local path resolves delegation exactly as Auth's resolve operation does
        // (PrincipalContextResolver#resolveDelegated): same realm, tenant and roles as alice's
        // own resolution there, with the service recorded as the acting service.
        PrincipalContextResolution viaAuth = world.resolver().resolve(world.identity("svc-deleg@acme")
                .applicationId(PrincipalFixture.APP).actingOnBehalfOf(null, "alice@acme").build());
        for (String header : new String[]{DelegatedIdentityHeaders.EFFECTIVE_USER_ID,
                DelegatedIdentityHeaders.EFFECTIVE_SUBJECT}) {
            String target = DelegatedIdentityHeaders.EFFECTIVE_USER_ID.equals(header) ? "alice@acme" : "sub-alice";
            PrincipalContext delegated = viaFilter("svc-deleg@acme", PrincipalFixture.APP, Map.of(header, target));
            assertEquals("alice@acme", delegated.getUserId(), header);
            assertEquals(viaAuth.context().getDefaultRealm(), delegated.getDefaultRealm(), header);
            assertEquals(viaAuth.context().getDataDomain(), delegated.getDataDomain(), header);
            assertArrayEquals(viaAuth.context().getRoles(), delegated.getRoles(), header);
            assertEquals("alice@acme", delegated.getActingOnBehalfOfUserId());
            assertEquals("svc-deleg@acme", delegated.getImpersonatedByUserId());
            assertEquals(Map.of("userId", "svc-deleg@acme", "subject", "sub-svc-deleg"),
                    delegated.getCustomProperties().get(SecurityFilter.ACTING_SERVICE_PROPERTY));
        }
        // Without the header it runs as itself.
        PrincipalContext itself = viaFilter("svc-deleg@acme", PrincipalFixture.APP, Map.of());
        assertEquals("svc-deleg@acme", itself.getUserId());
        assertEquals(PrincipalContext.SCOPE_SERVICE, itself.getScope());
        assertNull(itself.getActingOnBehalfOfUserId());
    }

    @Test
    void anApplicationAgnosticReceiverResolvesDelegationForTheApplicationTheRequestNames() {
        // Auth itself configures no application; its operations name one (applicationId), and the
        // delegation is checked for that application. Without the sudo header the realm is the
        // delegation's realm: the one alice resolves to for the application, as at the receiver.
        Map<String, String> acting = Map.of(DelegatedIdentityHeaders.EFFECTIVE_USER_ID, "alice@acme");
        PrincipalContext delegated = viaFilter("svc-deleg@acme", null, acting,
                Map.of(SecurityFilter.APPLICATION_ID_PARAMETER, PrincipalFixture.APP));
        PrincipalContext receiver = world.resolver().resolve(world.identity("alice@acme")
                .applicationId(PrincipalFixture.APP).build()).context();
        assertEquals("alice@acme", delegated.getUserId());
        assertEquals(PrincipalFixture.APP, delegated.getApplicationId());
        assertEquals(receiver.getDefaultRealm(), delegated.getDefaultRealm());
        assertArrayEquals(receiver.getRoles(), delegated.getRoles());

        ForbiddenException unnamed = assertThrows(ForbiddenException.class,
                () -> viaFilter("svc-deleg@acme", null, acting));
        assertTrue(unnamed.getMessage().startsWith("DELEGATION_NOT_ALLOWED"), unnamed.getMessage());
    }

    @Test
    void anApplicationTokenActingOnBehalfOfAnIdentityIsRefused() {
        for (String application : new String[]{PrincipalFixture.APP, null}) {
            ForbiddenException refused = assertThrows(ForbiddenException.class,
                    () -> viaFilter("svc-app@acme", application,
                            Map.of(DelegatedIdentityHeaders.EFFECTIVE_USER_ID, "alice@acme"),
                            Map.of(SecurityFilter.APPLICATION_ID_PARAMETER, PrincipalFixture.APP)), String.valueOf(application));
            assertTrue(refused.getMessage().startsWith("DELEGATION_NOT_ALLOWED"), refused.getMessage());
        }
    }

    @Test
    void aDelegatingServiceNotGrantedTheApplicationIsRefusedApplicationNotGranted() {
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> viaFilter("svc-deleg@acme", null, Map.of(DelegatedIdentityHeaders.EFFECTIVE_USER_ID, "alice@acme"),
                        Map.of(SecurityFilter.APPLICATION_ID_PARAMETER, "another-application")));
        assertEquals(403, refused.status());
        assertEquals("APPLICATION_NOT_GRANTED", refused.code());
    }

    @Test
    void anExplicitSudoRealmIsTheDelegationsRealmAndStaysInsideItsScope() {
        Map<String, String> headers = new HashMap<>(Map.of(DelegatedIdentityHeaders.EFFECTIVE_USER_ID, "alice@acme"));
        headers.put("X-Realm", "acme-D2");
        PrincipalContext delegated = viaFilter("svc-deleg@acme", PrincipalFixture.APP, headers);
        assertEquals("acme-D2", delegated.getDefaultRealm());
    }

    @Test
    void theIdentityModeRequestCarriesOnlyTheIdentityAndTheRequestsSelections() {
        SecurityFilter filter = filter("svc-app@acme", PrincipalFixture.APP);
        PrincipalContextRequest request = filter.identityOnlyServiceRequest("acme-D1", null, PrincipalFixture.APP);
        assertTrue(request.identityOnly());
        assertNull(request.signedClaims());
        assertEquals("sub-svc-app", request.subject());
        assertTrue(request.userIdCandidates().isEmpty());
        assertEquals(PrincipalFixture.APP, request.applicationId());
        assertFalse(request.delegationRequested());
        assertFalse(request.impersonationRequested());
    }

    @Test
    void anApplicationAgnosticReceiverKeepsTheIdentityOnlyCredentialPath() {
        // No receiving application (Auth itself): no application grant can apply; the token's
        // claims beyond the identity are still not read and the credential's home realm applies.
        PrincipalContext viaFilter = viaFilter("svc-app@acme", null, Map.of());
        assertEquals("acme-D1", viaFilter.getDefaultRealm());
        assertEquals("svc-app@acme", viaFilter.getUserId());
        // The token's azp ("another-application") is never taken as the application.
        assertNull(viaFilter.getApplicationId());
        assertEquals("acme", viaFilter.getDataDomain().getTenantId(), "the token's tenantId claim is ignored");
    }

    @Test
    void theReceivingApplicationBeanNamesTheApplicationWhenNoSessionApplicationIsSet() {
        // quantum.application-registration.application-id reaches the filter through the
        // ReceivingApplication bean; the session application is unset.
        SecurityFilter filter = filter("svc-app@acme", null);
        ReceivingApplication receiving = new ReceivingApplication() {
            @Override
            public String applicationId() {
                return PrincipalFixture.APP;
            }
        };
        @SuppressWarnings("unchecked")
        Instance<ReceivingApplication> bean = mock(Instance.class);
        when(bean.isResolvable()).thenReturn(true);
        when(bean.get()).thenReturn(receiving);
        filter.receivingApplication = bean;
        assertEquals(PrincipalFixture.APP, filter.receivingApplicationId());

        Map<String, String> headers = new HashMap<>(Map.of("Authorization", "Bearer v2"));
        PrincipalContext viaFilter = filter.determinePrincipalContext(request(headers));
        assertEquals(PrincipalFixture.APP, viaFilter.getApplicationId());
        assertEquals(PrincipalContext.SCOPE_SERVICE, viaFilter.getScope());
    }

    @Test
    void anUnresolvableReceivingApplicationBeanFallsBackToTheSessionApplicationOnly() {
        SecurityFilter filter = filter("svc-app@acme", null);
        @SuppressWarnings("unchecked")
        Instance<ReceivingApplication> bean = mock(Instance.class);
        when(bean.isResolvable()).thenReturn(false);
        filter.receivingApplication = bean;
        assertNull(filter.receivingApplicationId(), "never the token's azp");
    }

    @Test
    void theApplicationAgnosticWarningIsLoggedOncePerProcess() {
        viaFilter("svc-app@acme", null, Map.of());
        // The first v2 token at an application-agnostic receiver has warned; later ones do not.
        assertFalse(SecurityFilter.warnApplicationAgnosticServiceToken());
    }

    private PrincipalContext viaFilter(String userId, String receivingApplication, Map<String, String> headers) {
        return viaFilter(userId, receivingApplication, headers, Map.of());
    }

    private PrincipalContext viaFilter(String userId, String receivingApplication, Map<String, String> headers,
                                       Map<String, String> query) {
        SecurityFilter filter = filter(userId, receivingApplication);
        Map<String, String> all = new HashMap<>(headers);
        all.put("Authorization", "Bearer v2");
        return filter.determinePrincipalContext(request(all, query));
    }

    /** A v2 token of {@code userId}'s credential: {@code token_type=service} and {@code jti == sub}, nothing else. */
    private SecurityFilter filter(String userId, String receivingApplication) {
        CredentialUserIdPassword credential = world.credential(userId);
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", credential.getSubject());
        claims.put("userId", credential.getUserId());
        claims.put("jti", credential.getSubject());
        claims.put(DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE);
        // A role-bearing token's claims, which identity mode must ignore.
        claims.put("realm", "pooled");
        claims.put("tenantId", "tenant-b");
        claims.put("azp", "another-application");
        JsonWebToken token = jwt(claims);

        SecurityFilter filter = new SecurityFilter();
        filter.jwt = token;
        filter.securityIdentity = identity(token);
        filter.systemDirectory = world.directory;
        filter.realmMembershipService = world.membership;
        filter.credentialRepo = world.credentialRepo;
        filter.identityRoleResolver = world.roleResolver;
        filter.securityUtils = world.securityUtils;
        filter.envConfigUtils = world.env;
        filter.sessionApplicationId = Optional.ofNullable(receivingApplication);
        @SuppressWarnings("unchecked")
        Instance<ApplicationAccessDirectory> directory = mock(Instance.class);
        when(directory.isResolvable()).thenReturn(true);
        when(directory.get()).thenReturn(world.access);
        filter.accessDirectory = directory;
        return filter;
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        return request(headers, Map.of());
    }

    private static ContainerRequestContext request(Map<String, String> headers, Map<String, String> query) {
        jakarta.ws.rs.core.MultivaluedMap<String, String> parameters = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        query.forEach(parameters::putSingle);
        jakarta.ws.rs.core.UriInfo uri = mock(jakarta.ws.rs.core.UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(parameters);
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return headers.get(String.valueOf(args[0]));
                    }
                    if ("getUriInfo".equals(method.getName())) {
                        return uri;
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
    }

    private static JsonWebToken jwt(Map<String, Object> claims) {
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> {
                    if ("getClaim".equals(method.getName())) {
                        return claims.get(String.valueOf(args[0]));
                    }
                    if ("getName".equals(method.getName()) || "getSubject".equals(method.getName())) {
                        return claims.get("sub");
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

    private static SecurityIdentity identity(JsonWebToken principal) {
        return (SecurityIdentity) Proxy.newProxyInstance(
                SecurityIdentity.class.getClassLoader(),
                new Class<?>[]{SecurityIdentity.class},
                (proxy, method, args) -> {
                    if ("getPrincipal".equals(method.getName())) {
                        return principal;
                    }
                    if ("getRoles".equals(method.getName())) {
                        return Set.of();
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
    }
}
