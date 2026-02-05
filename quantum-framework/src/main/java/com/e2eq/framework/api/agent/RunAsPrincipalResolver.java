package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.securityrules.PrincipalContext;

import java.util.Optional;

/**
 * Optional resolver to obtain a {@link PrincipalContext} for a given userId in a realm.
 * Used by {@link AgentExecuteHandler} when tenant config has {@link TenantAgentConfig#runAsUserId}:
 * tool execution runs under that user's security context.
 *
 * <p>Applications may provide a CDI bean implementing this interface (e.g. using UserProfile
 * and realm data to build PrincipalContext). When no bean is present, execute runs as the caller.
 *
 * @see TenantAgentConfig
 * @see AgentExecuteHandler
 */
public interface RunAsPrincipalResolver {

    /**
     * Resolves the PrincipalContext to use when running agent tools as the given user in the realm.
     *
     * @param realm the tenant/realm
     * @param userId the runAs userId from tenant config
     * @return principal context for that user in that realm, or empty to run as caller
     */
    Optional<PrincipalContext> resolvePrincipalContext(String realm, String userId);
}
