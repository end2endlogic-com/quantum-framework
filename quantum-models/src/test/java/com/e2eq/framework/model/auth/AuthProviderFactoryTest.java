package com.e2eq.framework.model.auth;

import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthProviderFactoryTest {

    @Test
    void missingProviderConfigDefaultsToCustomProvider() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = null;

        assertEquals(List.of("custom"), factory.getConfiguredProviderNames());
    }

    @Test
    void blankProviderConfigDefaultsToCustomProvider() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = " , ";

        assertEquals(List.of("custom"), factory.getConfiguredProviderNames());
    }

    @Test
    void configuredProviderChainPreservesOrderAndDedupes() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = " oidc, custom, oidc ";

        assertEquals(List.of("oidc", "custom"), factory.getConfiguredProviderNames());
    }

    @Test
    void explicitProviderOverrideAcceptsAnOrderedList() {
        TestFactory factory = new TestFactory(provider("custom"), provider("oidc"));
        factory.configuredAuthProviders = "custom";

        assertEquals(List.of("oidc", "custom"),
                factory.getLoginProviders("oidc,custom,oidc").stream()
                        .map(AuthProvider::getName)
                        .toList());
    }

    @Test
    void configuredProviderChainFailsWhenAProviderIsUnavailable() {
        TestFactory factory = new TestFactory(provider("custom"));
        factory.configuredAuthProviders = "custom,oidc";

        assertThrows(IllegalArgumentException.class, () -> factory.getLoginProviders(null));
    }

    @Test
    void explicitProviderOverrideFailsRatherThanUsingConfiguredFallback() {
        TestFactory factory = new TestFactory(provider("custom"));
        factory.configuredAuthProviders = "custom";

        assertThrows(IllegalArgumentException.class, () -> factory.getLoginProviders("oidc"));
    }

    private static AuthProvider provider(String name) {
        return new AuthProvider() {
            @Override
            public SecurityIdentity validateAccessToken(String token) {
                return null;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public LoginResponse login(String userId, String password) {
                return null;
            }

            @Override
            public LoginResponse refreshTokens(String refreshToken) {
                return null;
            }
        };
    }

    private static final class TestFactory extends AuthProviderFactory {
        private final Map<String, AuthProvider> providers = new HashMap<>();

        private TestFactory(AuthProvider... providers) {
            for (AuthProvider provider : providers) {
                this.providers.put(provider.getName(), provider);
            }
        }

        @Override
        public AuthProvider getProviderByName(String name) {
            return findProviderByName(name).orElseThrow(() ->
                    new IllegalArgumentException("Missing provider " + name));
        }

        @Override
        public Optional<AuthProvider> findProviderByName(String name) {
            return Optional.ofNullable(providers.get(name));
        }
    }
}
