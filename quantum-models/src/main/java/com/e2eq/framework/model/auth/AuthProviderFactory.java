
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

    public static final String DEFAULT_AUTH_PROVIDER_CHAIN = "custom";

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
        return getProviderByName(getConfiguredProviderNames().get(0));
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
     *   <li>Explicit request provider chain, when supplied. This replaces the configured
     *       chain and is tried in the caller-supplied order.</li>
     *   <li>Configured provider list from {@code auth.provider}, in order.</li>
     * </ol>
     * Every named provider must be available. Configuration errors fail immediately
     * rather than changing authentication semantics through silent provider skipping.
     */
    public List<AuthProvider> getLoginProviders(String requestedProviderNames) {
        List<String> providerNames = requestedProviderNames == null || requestedProviderNames.isBlank()
                ? getConfiguredProviderNames()
                : parseProviderNames(requestedProviderNames);
        if (providerNames.isEmpty()) {
            throw new IllegalArgumentException("The requested authentication provider chain is empty");
        }

        List<AuthProvider> providers = new ArrayList<>();
        for (String providerName : providerNames) {
            providers.add(getProviderByName(providerName));
        }
        return providers;
    }

    private List<String> parseProviderNames(String providerNames) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (providerNames != null) {
            for (String part : providerNames.split(",")) {
                if (part != null && !part.trim().isBlank()) {
                    names.add(part.trim());
                }
            }
        }
        return new ArrayList<>(names);
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
     * Resolve provider by JWT issuer claim. Unknown or missing issuers fail closed;
     * callers that intentionally want the configured default must call
     * {@link #getAuthProvider()} explicitly.
     */
    public AuthProvider getProviderForIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer is required to resolve an authentication provider");
        }

        for (String providerName : getConfiguredProviderNames()) {
            AuthProvider provider = getProviderByName(providerName);
            if (issuer.equals(provider.getIssuer())) {
                return provider;
            }
        }
        throw new IllegalArgumentException(String.format(
                "No configured authentication provider matches issuer '%s'", issuer));
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
