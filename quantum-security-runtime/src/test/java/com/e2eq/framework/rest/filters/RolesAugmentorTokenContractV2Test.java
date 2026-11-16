package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.model.securityrules.PrincipalIdentityAttributes;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @RolesAllowed} for a token contract v2 user access token on a receiver that holds the
 * credential store: the token names no roles, so the credential's grants decide.
 */
class RolesAugmentorTokenContractV2Test {

    private static final Map<String, Object> V2 = Map.of(
            "iss", "https://auth.example.test", "sub", "sub-alice", "userId", "alice@acme", "jti", "8f6c",
            "scope", "authToken", DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.ACCESS_TOKEN_TYPE);

    private final com.e2eq.framework.security.principal.PrincipalFixture world =
            new com.e2eq.framework.security.principal.PrincipalFixture();
    private final int[] legacyResolverCalls = new int[1];

    @Test
    void rolesAreThoseAuthResolvesForTheApplicationInTheHomeRealm() {
        SecurityIdentity augmented = augment(augmentor(), V2);
        assertEquals(expectedRoles(null), augmented.getRoles());
        assertEquals("acme-D1", PrincipalIdentityAttributes.rolesRealm(augmented));
        assertEquals("alice@acme", augmented.getAttribute("userId"));
        assertEquals(0, legacyResolverCalls[0], "never the token-role or session-role derivation");
    }

    @Test
    void xRealmSelectsTheRealmTheRolesAreResolvedFor() {
        RolesAugmentor augmentor = augmentor();
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> "getHeaderString".equals(method.getName()) && "X-Realm".equals(args[0])
                        ? "acme-D2" : null);
        SecurityIdentity augmented = augment(augmentor, V2);
        assertEquals(expectedRoles("acme-D2"), augmented.getRoles());
        assertEquals("acme-D2", PrincipalIdentityAttributes.rolesRealm(augmented));
    }

    @Test
    void aDisabledCredentialGetsNoRolesAtAll() {
        world.credential("alice@acme").setActiveStatus(com.e2eq.framework.model.persistent.base.ActiveStatus.INACTIVE);
        assertEquals(Set.of(), augment(augmentor(), V2).getRoles());
    }

    @Test
    void aSuspendedBindingGetsNoRolesAtAll() {
        world.bind("alice@acme", com.e2eq.framework.security.principal.PrincipalFixture.APP, "acme-D1",
                java.util.List.of("acme"), "acme", "editor")
                .setStatus(com.e2eq.framework.model.security.UserAppRole.STATUS_SUSPENDED);
        assertEquals(Set.of(), augment(augmentor(), V2).getRoles());
    }

    @Test
    void noApplicationIdGetsNoRolesAtAll() {
        RolesAugmentor augmentor = augmentor();
        augmentor.sessionApplicationId = Optional.empty();
        assertEquals(Set.of(), augment(augmentor, V2).getRoles());
    }

    @Test
    void aClaimsTrustingReceiverGivesAV2TokenNoRolesAtAll() {
        RolesAugmentor augmentor = augmentor();
        augmentor.trustTokenClaims = true;
        assertEquals(Set.of(), augment(augmentor, V2).getRoles());
    }

    @Test
    void theSelectedTenantIsPartOfTheQuestion() {
        RolesAugmentor augmentor = augmentor();
        Map<String, String> headers = Map.of("X-Realm", "pooled", "X-Tenant-Id", "tenant-z");
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> "getHeaderString".equals(method.getName()) ? headers.get(args[0]) : null);
        assertEquals(Set.of(), augment(augmentor, V2).getRoles(), "a tenant the assignment does not authorize");
    }

    @Test
    void theConfiguredSessionRealmApplies() {
        RolesAugmentor augmentor = augmentor();
        augmentor.sessionRealm = Optional.of("acme-D2");
        SecurityIdentity augmented = augment(augmentor, V2);
        assertEquals(expectedRoles("acme-D2"), augmented.getRoles());
        assertEquals("acme-D2", PrincipalIdentityAttributes.rolesRealm(augmented));
    }

    @Test
    void aV1TokenKeepsItsGroupsUntilStep4() {
        RolesAugmentor augmentor = augmentor();
        augmentor.sessionApplicationId = Optional.empty();
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "sub-alice"), Set.of("system"));
        assertEquals(Set.of("system", "user"), augmented.getRoles());
    }

    private Set<String> expectedRoles(String realm) {
        var request = com.e2eq.framework.security.principal.PrincipalContextRequest.identity("sub-alice", "alice@acme")
                .applicationId(com.e2eq.framework.security.principal.PrincipalFixture.APP)
                .realm(realm)
                .tenantSelectionOptional(true)
                .build();
        Set<String> roles = new java.util.HashSet<>(Set.of(world.resolver().resolve(request).context().getRoles()));
        roles.add("user");
        return roles;
    }

    // ------------------------------------------------------------------ fixtures

    private static SecurityIdentity augment(RolesAugmentor augmentor, Map<String, Object> claims) {
        return augment(augmentor, claims, Set.of());
    }

    private static SecurityIdentity augment(RolesAugmentor augmentor, Map<String, Object> claims, Set<String> groups) {
        JsonWebToken token = jwt(claims, groups);
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(token)
                .addRoles(groups)
                .build();
        return augmentor.augment(identity, null).await().indefinitely();
    }

    private RolesAugmentor augmentor() {
        RolesAugmentor augmentor = new RolesAugmentor();
        augmentor.sessionApplicationId = Optional.of(com.e2eq.framework.security.principal.PrincipalFixture.APP);
        augmentor.sessionRealm = Optional.empty();
        augmentor.principalContextResolver = world.resolver();
        augmentor.identityRoleResolver = new com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver() {
            @Override
            public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential,
                                                  String authorityRealm, String effectiveRealm, String applicationId) {
                legacyResolverCalls[0]++;
                return new String[0];
            }
        };
        augmentor.envConfigUtils = world.env;
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    throw new ContextNotActiveException();
                });
        augmentor.credentialRepo = new TestCredentialRepo(Optional.empty());
        return augmentor;
    }

    /** Vetoed so this plain unit-test double is not an application bean. */
    @jakarta.enterprise.inject.Vetoed
    private static final class TestCredentialRepo extends CredentialRepo {
        private final Optional<CredentialUserIdPassword> answer;

        TestCredentialRepo(Optional<CredentialUserIdPassword> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<CredentialUserIdPassword> findBySubject(String subject, String realm, boolean ignoreRules) {
            // Credentials live in the system realm; the augmentor's other lookups use the request realm.
            return "system-com".equals(realm) ? answer : Optional.empty();
        }
    }

    private static JsonWebToken jwt(Map<String, Object> claims, Set<String> groups) {
        Map<String, Object> copy = new HashMap<>(claims);
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getClaim":
                            return copy.get(String.valueOf(args[0]));
                        case "getName":
                            return copy.get("sub");
                        case "getGroups":
                            return groups;
                        default:
                            break;
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
