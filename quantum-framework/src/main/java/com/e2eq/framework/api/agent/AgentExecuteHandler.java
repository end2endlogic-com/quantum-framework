package com.e2eq.framework.api.agent;

import com.e2eq.framework.api.query.QueryGatewayResource;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

/**
 * Dispatches agent tool execution to the Query Gateway. Resolves realm from arguments,
 * applies tenant config (runAsUserId when resolver is present), and delegates to
 * {@link QueryGatewayResource} for each of the six gateway operations.
 *
 * @see AgentResource
 * @see TenantAgentConfigResolver
 * @see RunAsPrincipalResolver
 */
@ApplicationScoped
public class AgentExecuteHandler {

    private static final String AREA = "integration";
    private static final String DOMAIN = "query";

    @Inject
    QueryGatewayResource queryGatewayResource;

    @Inject
    TenantAgentConfigResolver tenantAgentConfigResolver;

    @Inject
    jakarta.enterprise.inject.Instance<RunAsPrincipalResolver> runAsPrincipalResolver;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    /**
     * Executes the named tool with the given arguments. Realm is resolved from
     * arguments.realm or the current principal's default realm. When tenant config
     * has runAsUserId and a {@link RunAsPrincipalResolver} resolves a principal,
     * execution runs under that user's context.
     *
     * @param tool tool name (query_rootTypes, query_plan, query_find, query_save, query_delete, query_deleteMany)
     * @param arguments JSON-like arguments for the gateway (rootType, query, entity, id, realm, page, sort)
     * @return response from the underlying gateway operation
     */
    public Response execute(String tool, Map<String, Object> arguments) {
        if (tool == null || tool.isBlank()) {
            throw new BadRequestException("tool is required");
        }

        String realm = resolveRealmFromArguments(arguments);
        Optional<TenantAgentConfig> tenantConfig = tenantAgentConfigResolver.resolve(realm);
        Optional<PrincipalContext> runAsPrincipal = resolveRunAsPrincipal(tenantConfig);

        if (runAsPrincipal.isPresent()) {
            PrincipalContext asPrincipal = runAsPrincipal.get();
            ResourceContext resource = resourceContextForTool(asPrincipal, tool, realm);
            return SecurityCallScope.runWithContexts(asPrincipal, resource, () -> dispatch(tool, arguments, realm, tenantConfig));
        }
        return dispatch(tool, arguments, realm, tenantConfig);
    }

    private String resolveRealmFromArguments(Map<String, Object> arguments) {
        if (arguments != null && arguments.get("realm") instanceof String s && !s.isBlank()) {
            return s;
        }
        return SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDefaultRealm)
                .orElse(defaultRealm);
    }

    private Optional<PrincipalContext> resolveRunAsPrincipal(Optional<TenantAgentConfig> tenantConfig) {
        if (tenantConfig.isEmpty() || tenantConfig.get().runAsUserId == null || tenantConfig.get().runAsUserId.isBlank()) {
            return Optional.empty();
        }
        if (runAsPrincipalResolver.isUnsatisfied()) {
            return Optional.empty();
        }
        return runAsPrincipalResolver.get().resolvePrincipalContext(tenantConfig.get().realm, tenantConfig.get().runAsUserId);
    }

    private ResourceContext resourceContextForTool(PrincipalContext principal, String tool, String realm) {
        String action = actionForTool(tool);
        return SecurityCallScope.resource(principal, realm, AREA, DOMAIN, action);
    }

    private static String actionForTool(String tool) {
        return switch (tool) {
            case "query_rootTypes" -> "listRootTypes";
            case "query_plan" -> "plan";
            case "query_find" -> "find";
            case "query_save" -> "save";
            case "query_delete" -> "delete";
            case "query_deleteMany" -> "deleteMany";
            default -> "EXECUTE";
        };
    }

    private Response dispatch(String tool, Map<String, Object> arguments, String realm, Optional<TenantAgentConfig> tenantConfig) {
        switch (tool) {
            case "query_rootTypes" -> {
                return Response.ok(queryGatewayResource.listRootTypes()).build();
            }
            case "query_plan" -> {
                QueryGatewayResource.PlanRequest req = objectMapper.convertValue(arguments != null ? arguments : Map.of(), QueryGatewayResource.PlanRequest.class);
                return Response.ok(queryGatewayResource.plan(req)).build();
            }
            case "query_find" -> {
                QueryGatewayResource.FindRequest req = objectMapper.convertValue(arguments != null ? arguments : Map.of(), QueryGatewayResource.FindRequest.class);
                if (req.realm == null || req.realm.isBlank()) req.realm = realm;
                Integer maxLimit = tenantConfig.map(c -> c.maxFindLimit).orElse(null);
                if (maxLimit != null && req.page != null && req.page.limit != null && req.page.limit > maxLimit) {
                    req.page.limit = maxLimit;
                }
                return queryGatewayResource.find(req);
            }
            case "query_save" -> {
                QueryGatewayResource.SaveRequest req = objectMapper.convertValue(arguments != null ? arguments : Map.of(), QueryGatewayResource.SaveRequest.class);
                if (req.realm == null || req.realm.isBlank()) req.realm = realm;
                return queryGatewayResource.save(req);
            }
            case "query_delete" -> {
                QueryGatewayResource.DeleteRequest req = objectMapper.convertValue(arguments != null ? arguments : Map.of(), QueryGatewayResource.DeleteRequest.class);
                if (req.realm == null || req.realm.isBlank()) req.realm = realm;
                return queryGatewayResource.delete(req);
            }
            case "query_deleteMany" -> {
                QueryGatewayResource.DeleteManyRequest req = objectMapper.convertValue(arguments != null ? arguments : Map.of(), QueryGatewayResource.DeleteManyRequest.class);
                if (req.realm == null || req.realm.isBlank()) req.realm = realm;
                return queryGatewayResource.deleteMany(req);
            }
            default -> {
                Log.warnf("Unknown agent tool: %s", tool);
                throw new BadRequestException("Unknown tool: " + tool + ". Use query_rootTypes, query_plan, query_find, query_save, query_delete, query_deleteMany.");
            }
        }
    }
}
