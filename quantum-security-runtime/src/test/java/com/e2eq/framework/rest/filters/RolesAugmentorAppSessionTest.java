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
        assertEquals(Set.of("editor", "di-editor", "user"), augmented.getRoles());
        assertEquals("u1", augmented.getAttribute("userId"));
        assertEquals("decision-intelligence", resolverApplication[0]);
        assertEquals("di-session-realm", resolverRealm[0]);
    }

    @Test
    void tokenWithoutAzpIsForeignInAnApplicationSession() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
        SecurityIdentity augmented = augment(augmentor, Map.of("sub", "s1"));
        assertEquals(Set.of("editor", "di-editor", "user"), augmented.getRoles());
    }

    @Test
    void sessionApplicationsOwnTokenKeepsItsGroups() {
        RolesAugmentor augmentor = augmentor("decision-intelligence");
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
    private final String[] resolverRealm = new String[1];

    private RolesAugmentor augmentor(String sessionApplication) {
        RolesAugmentor augmentor = new RolesAugmentor();
        augmentor.sessionApplicationId = Optional.ofNullable(sessionApplication);
        augmentor.sessionRealm = Optional.of("di-session-realm");
        augmentor.identityRoleResolver = new com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver() {
            @Override
            public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential,
                                                  String authorityRealm, String effectiveRealm, String applicationId) {
                if (identity != null) {
                    throw new AssertionError("token roles must not reach session role resolution");
                }
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
        augmentor.credentialRepo = new CredentialRepo() {
            @Override
            public Optional<CredentialUserIdPassword> findBySubject(String subject, String realmId, boolean ignoreRules) {
                return Optional.of(CredentialUserIdPassword.builder()
                        .userId("u1")
                        .subject(subject)
                        .domainContext(com.e2eq.framework.model.security.DomainContext.builder()
                                .tenantId("t1").defaultRealm("di-realm").orgRefName("org1").accountId("acct1").build())
                        .lastUpdate(new Date())
                        .roles(new String[]{"editor"})
                        .build());
            }
        };
        return augmentor;
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
