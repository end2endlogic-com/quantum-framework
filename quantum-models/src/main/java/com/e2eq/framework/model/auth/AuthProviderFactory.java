
package com.e2eq.framework.model.auth;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AuthProviderFactory {

    public static final String DEFAULT_AUTH_PROVIDER_CHAIN = "custom,oidc";

    @ConfigProperty(name = "auth.provider", defaultValue = DEFAULT_AUTH_PROVIDER_CHAIN)
    String configuredAuthProviders; // supports comma-separated list (for example "custom,oidc")

    @Inject
    Instance<AuthProvider> authProviders;

    // Note: Do NOT inject framework services here to avoid cross-module coupling.

    /**
     * Returns the first (default) configured provider.
     * Backward compatible — single-value config works as before.
     */
    public AuthProvider getAuthProvider() {
        for (String providerName : getConfiguredProviderNames()) {
            Optional<AuthProvider> provider = findProviderByName(providerName);
            if (provider.isPresent()) {
                return provider.get();
            }
        }
        throw new IllegalArgumentException(String.format(
                "None of the configured auth providers are available: %s", getConfiguredProviderNames()));
    }

    public List<String> getConfiguredProviderNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (configuredAuthProviders != null) {
            for (String part : configuredAuthProviders.split(",")) {
                String name = part == null ? "" : part.trim();
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            for (String part : DEFAULT_AUTH_PROVIDER_CHAIN.split(",")) {
                String name = part.trim();
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Returns the auth providers discovered by CDI.
     *
     * <p>This is the canonical runtime inventory of provider implementations
     * visible to the factory. Configuration may name additional providers, but
     * login can only use providers that are present here.</p>
     */
    public List<AuthProvider> getDiscoveredProviders() {
        List<AuthProvider> discovered = new ArrayList<>();
        for (AuthProvider authProvider : authProviders) {
            discovered.add(authProvider);
        }
        return discovered;
    }

    public List<String> getDiscoveredProviderNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (AuthProvider authProvider : getDiscoveredProviders()) {
            names.add(authProvider.getName());
        }
        return new ArrayList<>(names);
    }

    /**
     * Returns the login providers to try for a user-facing login attempt.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit request provider, when supplied. This disables fallback.</li>
     *   <li>Credential-level provider override, when supplied. This must exist.</li>
     *   <li>Configured provider list from {@code auth.provider}, in order. Missing
     *       configured providers are skipped so open-source services can list optional
     *       enterprise providers without depending on their jars.</li>
     * </ol>
     */
    public List<AuthProvider> getLoginProviders(String requestedProviderName, String credentialProviderName) {
        if (requestedProviderName != null && !requestedProviderName.isBlank()) {
            return List.of(getProviderByName(requestedProviderName));
        }

        List<AuthProvider> providers = new ArrayList<>();
        if (credentialProviderName != null && !credentialProviderName.isBlank()) {
            providers.add(getProviderByName(credentialProviderName));
        }

        LinkedHashSet<String> configuredNames = new LinkedHashSet<>(getConfiguredProviderNames());
        if (credentialProviderName != null && !credentialProviderName.isBlank()) {
            configuredNames.removeIf(name -> name.equalsIgnoreCase(credentialProviderName.trim()));
        }
        for (String name : configuredNames) {
            findProviderByName(name).ifPresent(providers::add);
        }
        if (providers.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "None of the configured auth providers are available: %s", getConfiguredProviderNames()));
        }
        return providers;
    }

    /**
     * Look up a specific provider by name.
     */
    public AuthProvider getProviderByName(String name) {
        return findProviderByName(name).orElseThrow(() ->
                new IllegalArgumentException(String.format("AuthProvider:%s was not found", name)));
    }

    public Optional<AuthProvider> findProviderByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (AuthProvider authProvider : authProviders) {
            if (authProvider.getName().equalsIgnoreCase(name.trim())) {
                return Optional.of(authProvider);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve provider by JWT issuer claim. Iterates configured providers
     * and matches against their getIssuer(). Falls back to default provider.
     */
    public AuthProvider getProviderForIssuer(String issuer) {
        if (issuer == null) return getAuthProvider();

        for (String providerName : getConfiguredProviderNames()) {
            Optional<AuthProvider> provider = findProviderByName(providerName);
            if (provider.isPresent() && issuer.equals(provider.get().getIssuer())) {
                return provider.get();
            }
        }
        // No match — fall back to default provider
        return getAuthProvider();
    }

    public UserManagement getUserManager() {
        AuthProvider authProvider = this.getAuthProvider();
        if (authProvider instanceof UserManagement um) {
            return um;
        } else {
            throw new IllegalArgumentException(
                    String.format("AuthProvider:%s does not implement UserManagement", authProvider.getName()));
        }
    }

    public UserManagement getUserManager(String providerName) {
        AuthProvider authProvider = getProviderByName(providerName);
        if (authProvider instanceof UserManagement um) {
            return um;
        } else {
            throw new IllegalArgumentException(
                    String.format("AuthProvider:%s does not implement UserManagement", providerName));
        }
    }

    // Canonical validation is implemented in quantum-framework CanonicalIdentityService to keep module boundaries clean.

}
