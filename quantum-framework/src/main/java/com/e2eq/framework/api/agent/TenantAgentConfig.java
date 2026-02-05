package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Per-tenant agent configuration: runAs user, enabled tools, and optional limits.
 * Resolved by {@link TenantAgentConfigResolver} for a given realm.
 *
 * @see TenantAgentConfigResolver
 * @see AgentExecuteHandler
 */
@RegisterForReflection
public class TenantAgentConfig {

    /** Realm this config applies to. */
    public String realm;

    /**
     * Optional userId to run gateway operations as (security context).
     * When set, tool execution uses this user's PrincipalContext via {@link com.e2eq.framework.model.securityrules.SecurityCallScope}.
     */
    public String runAsUserId;

    /**
     * Optional list of tool names enabled for this tenant (e.g. query_find, query_save).
     * When empty or null, all tools from {@link AgentToolsProvider} are considered enabled.
     */
    public List<String> enabledTools;

    /**
     * Optional max find limit for this tenant.
     * When null, gateway default applies.
     */
    public Integer maxFindLimit;

    /**
     * Creates a config with no runAs, no tool filter, no limit.
     *
     * @param realm the realm
     * @return new config
     */
    public static TenantAgentConfig none(String realm) {
        TenantAgentConfig c = new TenantAgentConfig();
        c.realm = realm;
        c.runAsUserId = null;
        c.enabledTools = Collections.emptyList();
        c.maxFindLimit = null;
        return c;
    }
}
