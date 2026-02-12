package com.e2eq.framework.api.agent;

import java.util.Optional;

/**
 * Resolves per-tenant agent configuration for a given realm.
 * Used by {@link AgentToolsProvider} to filter enabled tools and by
 * {@link AgentExecuteHandler} to apply runAsUserId and limits.
 *
 * @see TenantAgentConfig
 * @see PropertyTenantAgentConfigResolver
 */
public interface TenantAgentConfigResolver {

    /**
     * Returns agent config for the given realm, if any.
     * When empty, no tenant-specific runAs or enabledTools apply.
     *
     * @param realm the tenant/realm identifier
     * @return config for that realm, or empty
     */
    Optional<TenantAgentConfig> resolve(String realm);
}
