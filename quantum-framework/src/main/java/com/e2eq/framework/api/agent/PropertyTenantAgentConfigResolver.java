package com.e2eq.framework.api.agent;

import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.config.ConfigProvider;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Property-based implementation of {@link TenantAgentConfigResolver}.
 * Reads per-realm config from MicroProfile Config:
 * <ul>
 *   <li>{@code quantum.agent.tenant.<realm>.runAsUserId} — optional userId for runAs</li>
 *   <li>{@code quantum.agent.tenant.<realm>.enabledTools} — optional comma-separated tool names</li>
 *   <li>{@code quantum.agent.tenant.<realm>.maxFindLimit} — optional integer limit for find</li>
 * </ul>
 * Realm is used as-is in the key; for realms with special characters use the config source's
 * escaping (e.g. quoted keys in application.properties).
 *
 * @see TenantAgentConfigResolver
 * @see TenantAgentConfig
 */
@ApplicationScoped
@DefaultBean
@RegisterForReflection
public class PropertyTenantAgentConfigResolver implements TenantAgentConfigResolver {

    private static final String PREFIX = "quantum.agent.tenant.";

    @Override
    public Optional<TenantAgentConfig> resolve(String realm) {
        if (realm == null || realm.isBlank()) {
            return Optional.empty();
        }
        String runAsKey = PREFIX + realm + ".runAsUserId";
        String enabledKey = PREFIX + realm + ".enabledTools";
        String limitKey = PREFIX + realm + ".maxFindLimit";

        Optional<String> runAs = ConfigProvider.getConfig().getOptionalValue(runAsKey, String.class);
        Optional<String> enabled = ConfigProvider.getConfig().getOptionalValue(enabledKey, String.class);
        Optional<Integer> limit = ConfigProvider.getConfig().getOptionalValue(limitKey, Integer.class);

        if (runAs.isEmpty() && enabled.isEmpty() && limit.isEmpty()) {
            return Optional.empty();
        }

        TenantAgentConfig config = new TenantAgentConfig();
        config.realm = realm;
        config.runAsUserId = runAs.orElse(null);
        config.enabledTools = parseEnabledTools(enabled.orElse(null));
        config.maxFindLimit = limit.orElse(null);
        return Optional.of(config);
    }

    private static List<String> parseEnabledTools(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
