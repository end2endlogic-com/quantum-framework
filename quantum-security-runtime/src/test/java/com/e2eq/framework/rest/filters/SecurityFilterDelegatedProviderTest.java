package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.ClaimsAuthProvider;
import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityFilterDelegatedProviderTest {

    @Test
    void trustedClaimsUseProviderBeforeAnyLocalIdentityRepository() throws Exception {
        SecurityFilter filter = new SecurityFilter();
        filter.trustTokenClaims = true;
        filter.authProviderFactory = new StubAuthProviderFactory(new StubClaimsProvider());
        filter.jwt = jwtWithSubject("subject-123");

        EnvConfigUtils envConfigUtils = new EnvConfigUtils();
        envConfigUtils.setSystemRealm("system-auth-shared");
        filter.envConfigUtils = envConfigUtils;

        // credentialRepo, profile/group repos, and SystemDirectory deliberately remain null.
        // Reaching any local identity/catalog path would make this test fail immediately.
        Method method = SecurityFilter.class.getDeclaredMethod(
                "buildJwtContextWithCredentials", String.class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(filter, "Bearer delegated-token", null);

        Field contextField = result.getClass().getDeclaredField("context");
        contextField.setAccessible(true);
        PrincipalContext context = (PrincipalContext) contextField.get(result);

        assertEquals("alice", context.getUserId());
        assertEquals("tenant-acme", context.getDefaultRealm());
        assertEquals("tenant-acme", context.getDataDomain().getTenantId());
        assertEquals("acme", context.getDataDomain().getOrgRefName());
        assertEquals("account-1", context.getDataDomain().getAccountNum());
        assertArrayEquals(new String[]{"scheduler-user"}, context.getRoles());
    }

    private static JsonWebToken jwtWithSubject(String subject) {
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> {
                    if ("getClaim".equals(method.getName()) && "sub".equals(args[0])) {
                        return subject;
                    }
                    if ("getName".equals(method.getName())) {
                        return subject;
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
            assertEquals("delegated-token", token);
            return ProviderClaims.builder("subject-123")
                    .username("alice")
                    .issuer("https://auth.example.test")
                    .tokenRoles(Set.of("scheduler-user"))
                    .putAttribute("realm", "tenant-acme")
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
