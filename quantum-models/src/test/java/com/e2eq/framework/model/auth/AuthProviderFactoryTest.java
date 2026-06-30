package com.e2eq.framework.model.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthProviderFactoryTest {

    @Test
    void missingProviderConfigDefaultsToCustomOidcChain() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = null;

        assertEquals(List.of("custom", "oidc"), factory.getConfiguredProviderNames());
    }

    @Test
    void blankProviderConfigDefaultsToCustomOidcChain() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = " , ";

        assertEquals(List.of("custom", "oidc"), factory.getConfiguredProviderNames());
    }

    @Test
    void configuredProviderChainPreservesOrderAndDedupes() {
        AuthProviderFactory factory = new AuthProviderFactory();
        factory.configuredAuthProviders = " oidc, custom, oidc ";

        assertEquals(List.of("oidc", "custom"), factory.getConfiguredProviderNames());
    }
}
