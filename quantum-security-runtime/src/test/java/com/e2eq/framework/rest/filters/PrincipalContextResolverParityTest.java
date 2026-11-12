package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalContextRequest;
import com.e2eq.framework.security.principal.PrincipalContextResolution;
import com.e2eq.framework.security.principal.PrincipalFixture;
import com.e2eq.framework.security.principal.PrincipalRef;
import io.quarkus.security.identity.SecurityIdentity;
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

/**
 * Rollout step 1 parity: for the same identity and request, identity-only resolution (the
 * {@code quantum-auth-service} endpoint) returns the principal that {@code SecurityFilter}'s
 * local credential path builds, and that principal is what the 1.4.x credential path built.
 *
 * <p>The filter is driven end to end through {@link SecurityFilter#determinePrincipalContext}
 * with a locally verified user token whose realm and tenant claims are the credential's own
 * (a token minted at sign-in), and no token roles; the identity-only request carries only the
 * identity.
 */
class PrincipalContextResolverParityTest {

    private final PrincipalFixture world = new PrincipalFixture();

    @Test
    void plainSignIn() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of());
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("alice@acme").build()).context();

        assertSamePrincipal(viaFilter, viaIdentity);
        assertEquals("acme-D1", viaFilter.getDefaultRealm());
        assertEquals(PrincipalFixture.APP, viaFilter.getApplicationId());
        assertEquals("acme", viaFilter.getDataDomain().getTenantId());
        assertEquals("alice@acme", viaFilter.getDataDomain().getOwnerId());
        assertArrayEquals(new String[]{"user", "d1-user"}, viaFilter.getRoles());
        assertFalse(viaFilter.isRealmOverrideActive());
        assertEquals("AUTHENTICATED", viaFilter.getScope());
    }

    @Test
    void realmSwitch() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of("X-Realm", "acme-D2"));
        PrincipalContext viaIdentity = world.resolver()
                .resolve(world.identity("alice@acme").realm("acme-D2").build()).context();

        assertSamePrincipal(viaFilter, viaIdentity);
        assertEquals("acme-D2", viaFilter.getDefaultRealm());
        assertEquals("acme-two", viaFilter.getDataDomain().getTenantId());
        assertEquals("acme-two", viaFilter.getDomainContext().getTenantId());
        assertArrayEquals(new String[]{"user", "d2-user"}, viaFilter.getRoles());
        assertTrue(viaFilter.isRealmOverrideActive());
        assertEquals("acme", viaFilter.getOriginalDataDomain().getTenantId());
    }

    @Test
    void tenantSwitchInSharedRealm() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of("X-Realm", "pooled", "X-Tenant-Id", "tenant-b"));
        PrincipalContext viaIdentity = world.resolver()
                .resolve(world.identity("alice@acme").realm("pooled").tenantId("tenant-b").build()).context();

        assertSamePrincipal(viaFilter, viaIdentity);
        assertEquals("pooled", viaFilter.getDefaultRealm());
        DataDomain dd = viaFilter.getDataDomain();
        assertEquals("tenant-b", dd.getTenantId());
        assertEquals("org-b", dd.getOrgRefName());
        assertEquals("acct-b", dd.getAccountNum());
        assertArrayEquals(new String[]{"user", "pool-user"}, viaFilter.getRoles());
    }

    @Test
    void areaOverridesAndDataDomainPolicyCarry() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of());
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("alice@acme").build()).context();

        assertEquals(Map.of("integration", "acme-archive"), viaFilter.getArea2RealmOverrides());
        assertEquals(viaFilter.getArea2RealmOverrides(), viaIdentity.getArea2RealmOverrides());
        assertEquals(world.credential("alice@acme").getDataDomainPolicy(), viaFilter.getDataDomainPolicy());
        assertEquals(viaFilter.getDataDomainPolicy(), viaIdentity.getDataDomainPolicy());
    }

    @Test
    void impersonation() {
        PrincipalContext viaFilter = viaFilter("admin@acme", Map.of("X-Impersonate-UserId", "alice@acme"));
        PrincipalContextResolution viaIdentity = world.resolver().resolve(
                world.identity("admin@acme").impersonate(null, "alice@acme").build());

        assertSamePrincipal(viaFilter, viaIdentity.context());
        assertEquals("alice@acme", viaFilter.getUserId());
        assertEquals("acme-D1", viaFilter.getDefaultRealm());
        assertEquals("admin@acme", viaFilter.getImpersonatedByUserId());
        assertEquals("sub-admin", viaFilter.getImpersonatedBySubject());
        assertArrayEquals(new String[]{"user", "d1-user"}, viaFilter.getRoles());
        assertEquals(Map.of("integration", "acme-archive"), viaFilter.getArea2RealmOverrides());
        assertEquals(new PrincipalRef("admin@acme", "sub-admin"), viaIdentity.impersonatedBy());
        assertEquals("alice@acme", viaIdentity.credential().getUserId());
        assertEquals("admin@acme", viaIdentity.callerCredential().getUserId());
    }

    @Test
    void applicationServiceIdentity() {
        // The filter's local credential path builds a service credential presented in a user token
        // like any principal; identity-only resolution builds the same principal, scoped SERVICE.
        PrincipalContext viaFilter = viaFilter("svc-app@acme", Map.of());
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("svc-app@acme").build()).context();

        assertSamePrincipalExceptScope(viaFilter, viaIdentity);
        assertEquals("AUTHENTICATED", viaFilter.getScope());
        assertEquals("SERVICE", viaIdentity.getScope());
        assertEquals("acme-D1", viaIdentity.getDefaultRealm());
        assertEquals("acme", viaIdentity.getDataDomain().getTenantId());
        assertArrayEquals(new String[]{"service"}, viaIdentity.getRoles());
    }

    @Test
    void delegationResolvesTheActedForIdentityAsIfItSignedIn() {
        PrincipalContext aliceSignedIn = viaFilter("alice@acme", Map.of());
        PrincipalContextResolution delegated = world.resolver().resolve(
                world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme").build());

        assertSamePrincipal(aliceSignedIn, delegated.context());
        assertEquals(new PrincipalRef("svc-deleg@acme", "sub-svc-deleg"), delegated.actingService());
        assertNull(delegated.impersonatedBy());
        assertEquals("alice@acme", delegated.credential().getUserId());
        assertEquals("svc-deleg@acme", delegated.callerCredential().getUserId());
    }

    @Test
    void delegationAppliesTheActedForIdentitysRealmAndTenantSelection() {
        PrincipalContext aliceInPool = viaFilter("alice@acme", Map.of("X-Realm", "pooled", "X-Tenant-Id", "tenant-a"));
        PrincipalContextResolution delegated = world.resolver().resolve(
                world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme")
                        .realm("pooled").tenantId("tenant-a").build());

        assertSamePrincipal(aliceInPool, delegated.context());
        assertEquals("tenant-a", delegated.context().getDataDomain().getTenantId());
    }

    @Test
    void rolesAreResolvedForTheReceivingApplication() {
        world.resolver().resolve(world.identity("alice@acme").realm("acme-D2").build());
        viaFilter("alice@acme", Map.of("X-Realm", "acme-D2"));
        assertEquals(List.of(PrincipalFixture.APP, PrincipalFixture.APP), world.roleApplications);
    }

    @Test
    void aPlatformOperationNeedsNoTenantOnEitherPath() {
        // alice reaches several tenants of the shared realm and defines no default: an ordinary
        // request must select one, a platform operation (tenantSelectionExempt / tenantSelection
        // "optional") keeps the realm-switched base principal on both paths.
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of("X-Realm", "pooled"), Set.of(),
                platformResource());
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("alice@acme").realm("pooled")
                .tenantSelectionOptional(true).build()).context();

        assertSamePrincipal(viaFilter, viaIdentity);
        assertEquals("pooled", viaIdentity.getDefaultRealm());
    }

    @Test
    void aPlatformOperationStillAuthorizesARequestedTenant() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of("X-Realm", "pooled", "X-Tenant-Id", "tenant-b"),
                Set.of(), platformResource());
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("alice@acme").realm("pooled")
                .tenantId("tenant-b").tenantSelectionOptional(true).build()).context();

        assertSamePrincipal(viaFilter, viaIdentity);
        assertEquals("tenant-b", viaIdentity.getDataDomain().getTenantId());
        com.e2eq.framework.security.principal.PrincipalResolutionException refused = assertThrows(
                com.e2eq.framework.security.principal.PrincipalResolutionException.class,
                () -> world.resolver().resolve(world.identity("alice@acme").realm("pooled")
                        .tenantId("tenant-z").tenantSelectionOptional(true).build()));
        assertEquals("TENANT_NOT_AUTHORIZED", refused.code());
    }

    @Test
    void anOrdinaryRequestStillRequiresATenantSelection() {
        com.e2eq.framework.security.principal.PrincipalResolutionException refused = assertThrows(
                com.e2eq.framework.security.principal.PrincipalResolutionException.class,
                () -> world.resolver().resolve(world.identity("alice@acme").realm("pooled").build()));
        assertEquals("TENANT_SELECTION_REQUIRED", refused.code());
    }

    @Test
    void delegationCarriesTheTenantSelectionRule() {
        PrincipalContextResolution delegated = world.resolver().resolve(
                world.identity("svc-deleg@acme").actingOnBehalfOf(null, "alice@acme").realm("pooled")
                        .tenantSelectionOptional(true).build());
        PrincipalContext aliceDirect = world.resolver().resolve(world.identity("alice@acme").realm("pooled")
                .tenantSelectionOptional(true).build()).context();

        assertSamePrincipal(aliceDirect, delegated.context());
    }

    @com.e2eq.framework.annotations.FunctionalMapping(area = "security", domain = "identity")
    static final class PlatformIdentityResource {
        public void read() {
        }
    }

    private static jakarta.ws.rs.container.ResourceInfo platformResource() {
        return new jakarta.ws.rs.container.ResourceInfo() {
            @Override
            public java.lang.reflect.Method getResourceMethod() {
                try {
                    return PlatformIdentityResource.class.getDeclaredMethod("read");
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public Class<?> getResourceClass() {
                return PlatformIdentityResource.class;
            }
        };
    }

    // --- where the two paths deliberately differ ---

    @Test
    void onlyTheSignedPathHonoursTokenRoles() {
        PrincipalContext viaFilter = viaFilter("alice@acme", Map.of(), Set.of("token-role"));
        PrincipalContext viaIdentity = world.resolver().resolve(world.identity("alice@acme").build()).context();

        assertArrayEquals(new String[]{"token-role", "user", "d1-user"}, viaFilter.getRoles());
        assertArrayEquals(new String[]{"user", "d1-user"}, viaIdentity.getRoles());
    }

    @Test
    void theFilterKeepsItsLegacyExceptions() {
        // acme-D3 is not registered: the filter still answers with a plain 403, not a typed refusal.
        assertThrows(ForbiddenException.class, () -> viaFilter("alice@acme", Map.of("X-Realm", "acme-D3")));
    }

    // --- harness ---

    private static void assertSamePrincipal(PrincipalContext expected, PrincipalContext actual) {
        assertSamePrincipalExceptScope(expected, actual);
        assertEquals(expected.getScope(), actual.getScope(), "scope");
    }

    private static void assertSamePrincipalExceptScope(PrincipalContext expected, PrincipalContext actual) {
        assertEquals(expected.getDefaultRealm(), actual.getDefaultRealm(), "realm");
        assertEquals(expected.getApplicationId(), actual.getApplicationId(), "applicationId");
        assertEquals(expected.getUserId(), actual.getUserId(), "userId");
        assertArrayEquals(expected.getRoles(), actual.getRoles(), "roles");
        assertEquals(expected.getDomainContext(), actual.getDomainContext(), "domainContext");
        assertEquals(expected.getDataDomain(), actual.getDataDomain(), "dataDomain");
        assertEquals(expected.getArea2RealmOverrides(), actual.getArea2RealmOverrides(), "area2RealmOverrides");
        assertEquals(expected.getDataDomainPolicy(), actual.getDataDomainPolicy(), "dataDomainPolicy");
        assertEquals(expected.getImpersonatedBySubject(), actual.getImpersonatedBySubject(), "impersonatedBySubject");
        assertEquals(expected.getImpersonatedByUserId(), actual.getImpersonatedByUserId(), "impersonatedByUserId");
        assertEquals(expected.isRealmOverrideActive(), actual.isRealmOverrideActive(), "realmOverrideActive");
        assertEquals(expected.getOriginalDataDomain(), actual.getOriginalDataDomain(), "originalDataDomain");
    }

    private PrincipalContext viaFilter(String userId, Map<String, String> headers) {
        return viaFilter(userId, headers, Set.of());
    }

    /** SecurityFilter's local credential path for a sign-in token of {@code userId}. */
    private PrincipalContext viaFilter(String userId, Map<String, String> headers, Set<String> tokenRoles) {
        return viaFilter(userId, headers, tokenRoles, null);
    }

    private PrincipalContext viaFilter(String userId, Map<String, String> headers, Set<String> tokenRoles,
                                       jakarta.ws.rs.container.ResourceInfo resourceInfo) {
        CredentialUserIdPassword credential = world.credential(userId);
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", credential.getSubject());
        claims.put("userId", credential.getUserId());
        claims.put("azp", PrincipalFixture.APP);
        claims.put("realm", credential.getDomainContext().getDefaultRealm());
        claims.put("tenantId", credential.getDomainContext().getTenantId());
        JsonWebToken token = jwt(claims);

        SecurityFilter filter = new SecurityFilter();
        filter.jwt = token;
        filter.securityIdentity = identity(token, tokenRoles);
        filter.systemDirectory = world.directory;
        filter.realmMembershipService = world.membership;
        filter.credentialRepo = world.credentialRepo;
        filter.identityRoleResolver = world.roleResolver;
        filter.securityUtils = world.securityUtils;
        filter.envConfigUtils = world.env;
        filter.sessionApplicationId = Optional.of(PrincipalFixture.APP);
        filter.resourceInfo = resourceInfo;

        Map<String, String> all = new HashMap<>(headers);
        all.put("Authorization", "Bearer signed-in");
        return filter.determinePrincipalContext(request(all));
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return headers.get(String.valueOf(args[0]));
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
                    if ("getName".equals(method.getName())) {
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

    private static SecurityIdentity identity(JsonWebToken principal, Set<String> roles) {
        return (SecurityIdentity) Proxy.newProxyInstance(
                SecurityIdentity.class.getClassLoader(),
                new Class<?>[]{SecurityIdentity.class},
                (proxy, method, args) -> {
                    if ("getPrincipal".equals(method.getName())) {
                        return principal;
                    }
                    if ("getRoles".equals(method.getName())) {
                        return roles;
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
    }
}
