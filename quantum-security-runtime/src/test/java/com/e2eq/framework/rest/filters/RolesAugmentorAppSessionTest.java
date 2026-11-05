package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * In an application session ({@code quantum.security.session.application-id}) a token minted for
 * another application must not satisfy {@code @RolesAllowed} with that application's roles.
 */
class RolesAugmentorAppSessionTest {

    @Test
    void unsetSessionApplicationKeepsTokenGroups() {
        RolesAugmentor augmentor = augmentor(null);
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1", "azp", "helixor-launchpad"));
        assertEquals(Set.of("system", "editor", "user"), augmented.getRoles());
    }

    @Test
    void foreignApplicationTokenLosesItsRolesAndKeepsTheCredentialRoles() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1", "azp", "helixor-launchpad"));
        // Session roles come from the resolver (which itself adds credential roles); the
        // contextRealm credential's flat roles are not added separately.
        assertEquals(Set.of("di-editor", "user"), augmented.getRoles());
        assertEquals("u1", augmented.getAttribute("userId"));
        assertNull(resolverIdentity[0], "a foreign token's roles must not reach role resolution");
        assertEquals("decision-intelligence", resolverApplication[0]);
        assertEquals("di-session-realm", resolverRealm[0]);
    }

    @Test
    void tokenWithoutAzpIsForeignInAnApplicationSession() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1"));
        assertEquals(Set.of("di-editor", "user"), augmented.getRoles());
    }

    @Test
    void withoutASessionRealmRolesResolveInTheTokensSignedRealm() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.sessionRealm = Optional.empty();
        augment(augmentor, Map.of("sub", "s1", "azp", "helixor-launchpad", "realm", "helixor-code-D1"));
        assertEquals("helixor-code-D1", resolverRealm[0]);
    }

    @Test
    void delegatedForeignTokenWithoutLocalCredentialGetsTheRealmAssignmentRoles() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.trustTokenClaims = true;
        augmentor.credentialRepo = new TestCredentialRepo((subject, realmId) -> {
                return Optional.empty();
        });
        String[] assignmentRealm = new String[1];
        augmentor.realmMembershipService = new com.e2eq.framework.system.membership.RealmMembershipService() {
            @Override
            public Optional<com.e2eq.framework.model.security.UserRealmRole> assignmentForUser(String userId, String realmRefName) {
                assignmentRealm[0] = realmRefName;
                com.e2eq.framework.model.security.UserRealmRole assignment = new com.e2eq.framework.model.security.UserRealmRole();
                assignment.setRoles(java.util.List.of("admin"));
                return "alice".equals(userId) ? Optional.of(assignment) : Optional.empty();
            }
        };
        SecurityIdentity augmented = augment(augmentor,
                Map.of("sub", "s1", "userId", "alice", "azp", "helixor-launchpad", "realm", "helixor-code-D1"));
        // "system" from the launch pad token is not carried (no authoritative flat roles).
        assertEquals(Set.of("admin", "user"), augmented.getRoles());
        assertEquals("di-session-realm", assignmentRealm[0]);
    }

    @Test
    void delegatedForeignTokenWithoutAUserIdClaimGetsNoAssignmentRoles() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.trustTokenClaims = true;
        augmentor.credentialRepo = new TestCredentialRepo((subject, realmId) -> {
                return Optional.empty();
        });
        augmentor.realmMembershipService = new com.e2eq.framework.system.membership.RealmMembershipService() {
            @Override
            public Optional<com.e2eq.framework.model.security.UserRealmRole> assignmentForUser(String userId, String realmRefName) {
                throw new AssertionError("no userId claim: the assignment must not be looked up by another name");
            }
        };
        SecurityIdentity augmented = augment(augmentor,
                Map.of("sub", "s1", "username", "alice", "azp", "helixor-launchpad"));
        assertEquals(Set.of("user"), augmented.getRoles());
    }

    @Test
    void delegatedForeignTokenUsesDelegatedRolesEvenWithALocalCredential() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.trustTokenClaims = true;
        augmentor.realmMembershipService = new com.e2eq.framework.system.membership.RealmMembershipService() {
            @Override
            public Optional<com.e2eq.framework.model.security.UserRealmRole> assignmentForUser(String userId, String realmRefName) {
                return Optional.empty();
            }
        };
        SecurityIdentity augmented = augment(augmentor,
                Map.of("sub", "s1", "userId", "u1", "azp", "helixor-launchpad", "realm", "helixor-code-D1"));
        assertEquals(0, resolverCalls[0], "delegated sessions must not pick up local application groups");
        assertEquals(Set.of("user"), augmented.getRoles());
    }

    @Test
    void foreignTokenWithXRealmFindsTheSystemRealmCredential() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        headers(augmentor, "tenant-realm");
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1", "azp", "helixor-launchpad"));
        assertEquals(Set.of("di-editor", "user"), augmented.getRoles());
        assertEquals("tenant-realm", resolverRealm[0]);
    }

    @Test
    void ownApplicationTokenMovedToTheSessionRealmGetsThatRealmsRoles() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        SecurityIdentity augmented = augment(augmentor,
                Map.of("sub", "s1", "azp", "decision-intelligence", "realm", "helixor-code-D1"));
        assertEquals("di-session-realm", resolverRealm[0]);
        assertEquals(1, resolverCalls[0]);
        // Same application: the resolver gets the identity and drops its roles itself on a realm change.
        assertEquals(Set.of("di-editor", "user"), augmented.getRoles());
    }

    @Test
    void ownApplicationTokenInItsOwnRealmKeepsItsGroups() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.sessionRealm = Optional.of("helixor-code-D1");
        SecurityIdentity augmented = augment(augmentor,
                Map.of("sub", "s1", "azp", "decision-intelligence", "realm", "helixor-code-D1"));
        assertEquals(0, resolverCalls[0]);
        assertEquals(Set.of("system", "editor", "user"), augmented.getRoles());
    }

    private static void headers(RolesAugmentor augmentor, String realm) {
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> "getHeaderString".equals(method.getName()) && "X-Realm".equals(args[0])
                        ? realm : null);
    }

    @Test
    void sessionApplicationsOwnTokenKeepsItsGroups() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        augmentor.sessionRealm = Optional.empty();
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1", "azp", "decision-intelligence"));
        assertEquals(Set.of("system", "editor", "user"), augmented.getRoles());
    }

    @Test
    void serviceTokensKeepTheirGroups() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        SecurityIdentity augmented = augment(augmentor, Map.of(
                "sub", "s1",
                "azp", "helixor-code-api",
                DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE));
        assertEquals(Set.of("system", "editor", "user"), augmented.getRoles());
    }

    private static SecurityIdentity augment(RolesAugmentor augmentor, Map<String, Object> claims) {
        JsonWebToken token = jwt(claims, Set.of("system"));
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(token)
                .addRoles(Set.of("system"))
                .build();
        return augmentor.augment(identity, null).await().indefinitely();
    }

    private final String[] resolverApplication = new String[1];
    private final SecurityIdentity[] resolverIdentity = new SecurityIdentity[1];
    private final int[] resolverCalls = new int[1];
    private final String[] resolverRealm = new String[1];

    private RolesAugmentor augmentor(String sessionApplication) {
        RolesAugmentor augmentor = new RolesAugmentor();
        augmentor.sessionApplicationId = Optional.ofNullable(sessionApplication);
        augmentor.sessionRealm = sessionApplication == null ? Optional.empty() : Optional.of("di-session-realm");
        augmentor.identityRoleResolver = new com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver() {
            @Override
            public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential,
                                                  String authorityRealm, String effectiveRealm, String applicationId) {
                resolverIdentity[0] = identity;
                resolverCalls[0]++;
                resolverApplication[0] = applicationId;
                resolverRealm[0] = effectiveRealm;
                return new String[]{"di-editor"};
            }
        };
        EnvConfigUtils env = new EnvConfigUtils();
        env.setSystemRealm("system-com");
        augmentor.envConfigUtils = env;
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    throw new ContextNotActiveException();
                });
        augmentor.credentialRepo = new TestCredentialRepo((subject, realmId) -> {
                if (!"system-com".equals(realmId)) {
                    return Optional.empty();
                }
                return Optional.of(CredentialUserIdPassword.builder()
                        .userId("u1")
                        .subject(subject)
                        .domainContext(com.e2eq.framework.model.security.DomainContext.builder()
                                .tenantId("t1").defaultRealm("di-realm").orgRefName("org1").accountId("acct1").build())
                        .lastUpdate(new Date())
                        .roles(new String[]{"editor"})
                        .build());
        });
        return augmentor;
    }

    /** Named and vetoed so these plain unit-test doubles are not HTTP application beans. */
    @jakarta.enterprise.inject.Vetoed
    private static final class TestCredentialRepo extends CredentialRepo {
        private final java.util.function.BiFunction<String, String, Optional<CredentialUserIdPassword>> lookup;

        TestCredentialRepo(java.util.function.BiFunction<String, String, Optional<CredentialUserIdPassword>> lookup) {
            this.lookup = lookup;
        }

        @Override
        public Optional<CredentialUserIdPassword> findBySubject(String subject, String realm, boolean ignoreRules) {
            return lookup.apply(subject, realm);
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
