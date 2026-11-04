package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.ClaimsAuthProvider;
import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The application session settings ({@code quantum.security.session.application-id} and
 * {@code quantum.security.session.realm}) make a user's session run as the configured
 * application in the configured realm, whichever application or realm minted the token.
 * Unset, the token's azp and realm apply exactly as before.
 */
class SecurityFilterAppSessionTest {

    private static final String LAUNCHPAD_TOKEN_APP = "helixor-launchpad";

    // --- realm selection ---

    @Test
    void unsetSessionRealmSelectsNoRealm() {
        assertNull(SecurityFilter.sessionRealmSelection(null, null, true));
        assertEquals("customer-realm", SecurityFilter.sessionRealmSelection("customer-realm", null, true));
    }

    @Test
    void configuredSessionRealmAppliesToUserPrincipals() {
        assertEquals("di-realm", SecurityFilter.sessionRealmSelection(null, "di-realm", true));
        assertEquals("di-realm", SecurityFilter.sessionRealmSelection(" ", "di-realm", true));
    }

    @Test
    void explicitXRealmStillWinsOverTheSessionRealm() {
        assertEquals("customer-realm", SecurityFilter.sessionRealmSelection("customer-realm", "di-realm", true));
    }

    @Test
    void sessionRealmDoesNotApplyToServiceOrAnonymousPrincipals() {
        assertNull(SecurityFilter.sessionRealmSelection(null, "di-realm", false));
    }

    // --- application id ---

    @Test
    void unsetSessionApplicationKeepsTheTokenAzp() {
        SecurityFilter filter = filterWithToken(Map.of("sub", "s1", "azp", LAUNCHPAD_TOKEN_APP));
        assertEquals(LAUNCHPAD_TOKEN_APP, filter.currentApplicationId());
    }

    @Test
    void configuredSessionApplicationReplacesTheTokenAzpForUsers() {
        SecurityFilter filter = filterWithToken(Map.of("sub", "s1", "azp", LAUNCHPAD_TOKEN_APP));
        filter.sessionApplicationId = Optional.of(" decision-intelligence ");
        assertEquals("decision-intelligence", filter.currentApplicationId());
    }

    @Test
    void blankSessionApplicationIsTreatedAsUnset() {
        SecurityFilter filter = filterWithToken(Map.of("sub", "s1", "azp", LAUNCHPAD_TOKEN_APP));
        filter.sessionApplicationId = Optional.of("  ");
        assertEquals(LAUNCHPAD_TOKEN_APP, filter.currentApplicationId());
    }

    @Test
    void serviceTokensKeepTheirOwnAzp() {
        SecurityFilter filter = filterWithToken(Map.of(
                "sub", "svc",
                "azp", "helixor-code-api",
                DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE));
        filter.sessionApplicationId = Optional.of("decision-intelligence");
        assertEquals("helixor-code-api", filter.currentApplicationId());
    }

    // --- role resolution ---

    @Test
    void rolesResolveForTheTokenApplicationWhenUnset() throws Exception {
        SecurityFilter filter = filterWithToken(Map.of("sub", "s1", "azp", LAUNCHPAD_TOKEN_APP));
        CapturingResolver resolver = new CapturingResolver();
        filter.identityRoleResolver = resolver;

        invokeResolveEffectiveRoles(filter, credential(), "helixor-code-D1");

        assertEquals(LAUNCHPAD_TOKEN_APP, resolver.applicationId);
        assertEquals("helixor-code-D1", resolver.effectiveRealm);
    }

    @Test
    void rolesResolveForTheSessionApplicationAndRealmWhenConfigured() throws Exception {
        SecurityFilter filter = filterWithToken(Map.of("sub", "s1", "azp", LAUNCHPAD_TOKEN_APP));
        filter.sessionApplicationId = Optional.of("decision-intelligence");
        filter.sessionRealm = Optional.of("di-realm");
        CapturingResolver resolver = new CapturingResolver();
        filter.identityRoleResolver = resolver;

        String realm = SecurityFilter.sessionRealmSelection(null, "di-realm", true);
        String[] roles = invokeResolveEffectiveRoles(filter, credential(), realm);

        assertEquals("decision-intelligence", resolver.applicationId);
        assertEquals("di-realm", resolver.effectiveRealm);
        assertEquals("u1", resolver.userId);
        assertArrayEquals(new String[]{"di-user"}, roles);
    }

    // --- delegated (trusted-issuer) principals ---

    @Test
    void delegatedPrincipalKeepsTheTokenAzpWhenUnset() throws Exception {
        PrincipalContext context = buildDelegatedContext(delegatedFilter());
        assertEquals(LAUNCHPAD_TOKEN_APP, context.getApplicationId());
        assertEquals("tenant-acme", context.getDefaultRealm());
    }

    @Test
    void delegatedPrincipalRunsAsTheSessionApplicationWhenConfigured() throws Exception {
        SecurityFilter filter = delegatedFilter();
        filter.sessionApplicationId = Optional.of("doc-audit");
        PrincipalContext context = buildDelegatedContext(filter);
        assertEquals("doc-audit", context.getApplicationId());
        assertEquals("alice", context.getUserId());
    }

    // --- helpers ---

    private static final class CapturingResolver extends IdentityRoleResolver {
        String applicationId;
        String effectiveRealm;
        String userId;

        @Override
        public String[] resolveEffectiveRoles(SecurityIdentity identity,
                                              CredentialUserIdPassword credential,
                                              String authorityRealm,
                                              String effectiveRealm,
                                              String applicationId) {
            this.applicationId = applicationId;
            this.effectiveRealm = effectiveRealm;
            this.userId = credential.getUserId();
            return new String[]{"di-user"};
        }
    }

    private static CredentialUserIdPassword credential() {
        return CredentialUserIdPassword.builder()
                .userId("u1")
                .subject("s1")
                .domainContext(com.e2eq.framework.model.security.DomainContext.builder()
                        .tenantId("t1").defaultRealm("helixor-code-D1").orgRefName("org1").accountId("acct1").build())
                .lastUpdate(new Date())
                .roles(new String[]{"user"})
                .build();
    }

    private static String[] invokeResolveEffectiveRoles(SecurityFilter filter,
                                                        CredentialUserIdPassword credential,
                                                        String realm) throws Exception {
        Method m = SecurityFilter.class.getDeclaredMethod(
                "resolveEffectiveRoles", SecurityIdentity.class, CredentialUserIdPassword.class, String.class);
        m.setAccessible(true);
        return (String[]) m.invoke(filter, filter.securityIdentity, credential, realm);
    }

    private static SecurityFilter filterWithToken(Map<String, Object> claims) {
        SecurityFilter filter = new SecurityFilter();
        JsonWebToken token = jwt(claims);
        filter.jwt = token;
        filter.securityIdentity = identity(token);
        return filter;
    }

    private static SecurityFilter delegatedFilter() {
        SecurityFilter filter = filterWithToken(Map.of("sub", "subject-123", "azp", LAUNCHPAD_TOKEN_APP));
        filter.trustTokenClaims = true;
        filter.authProviderFactory = new StubAuthProviderFactory(new StubClaimsProvider());
        EnvConfigUtils envConfigUtils = new EnvConfigUtils();
        envConfigUtils.setSystemRealm("system-auth-shared");
        filter.envConfigUtils = envConfigUtils;
        return filter;
    }

    private static PrincipalContext buildDelegatedContext(SecurityFilter filter) throws Exception {
        Method method = SecurityFilter.class.getDeclaredMethod(
                "buildJwtContextWithCredentials", String.class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(filter, "Bearer delegated-token", null);
        Field contextField = result.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        return (PrincipalContext) contextField.get(result);
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
                    if ("getName".equals(method.getName())) {
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

    private static final class StubAuthProviderFactory extends AuthProviderFactory {
        private final AuthProvider provider;

        private StubAuthProviderFactory(AuthProvider provider) {
            this.provider = provider;
        }

        @Override
        public AuthProvider getAuthProvider() {
            return provider;
        }
    }

    private static final class StubClaimsProvider implements ClaimsAuthProvider {
        @Override
        public ProviderClaims validateTokenToClaims(String token) {
            return ProviderClaims.builder("subject-123")
                    .username("alice")
                    .issuer("https://auth.example.test")
                    .tokenRoles(Set.of("scheduler-user"))
                    .putAttribute("realm", "tenant-acme")
                    .putAttribute("azp", LAUNCHPAD_TOKEN_APP)
                    .putAttribute("tenantId", "tenant-acme")
                    .putAttribute("orgRefName", "acme")
                    .putAttribute("accountNum", "account-1")
                    .build();
        }

        @Override
        public SecurityIdentity validateAccessToken(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return "stub-claims";
        }

        @Override
        public LoginResponse login(String userId, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginResponse refreshTokens(String refreshToken) {
            throw new UnsupportedOperationException();
        }
    }
}
