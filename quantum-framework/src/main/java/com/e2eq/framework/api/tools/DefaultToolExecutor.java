package com.e2eq.framework.api.tools;

import com.e2eq.framework.api.agent.AgentExecuteHandler;
import com.e2eq.framework.api.agent.GatewayInvocationResult;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.morphia.ToolProviderConfigRepo;
import com.e2eq.framework.model.persistent.tools.InvocationConfig;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
import com.e2eq.framework.model.persistent.tools.ToolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Default implementation of {@link ToolExecutor}. Routes QUANTUM_QUERY and QUANTUM_API
 * tools to the gateway via {@link AgentExecuteHandler}; other tool types return an error for Phase 1.
 */
@ApplicationScoped
public class DefaultToolExecutor implements ToolExecutor {

    private static final Set<String> GATEWAY_TOOL_NAMES = Set.of(
        "query_rootTypes", "query_plan", "query_find", "query_save", "query_delete", "query_deleteMany"
    );

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    @Inject
    ToolProviderConfigRepo toolProviderConfigRepo;

    @Inject
    AgentExecuteHandler agentExecuteHandler;

    @Inject
    ExternalRestInvoker externalRestInvoker;

    @Inject
    McpClient mcpClient;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    @Override
    public ToolResult execute(String toolRef, Map<String, Object> parameters, ExecutionContext context) {
        long start = System.currentTimeMillis();
        if (toolRef == null || toolRef.isBlank()) {
            return errorResult(null, ToolResult.ToolResultStatus.VALIDATION_ERROR, "toolRef is required", start);
        }

        String realm = context != null && context.getRealmId() != null && !context.getRealmId().isBlank()
            ? context.getRealmId()
            : defaultRealm;

        Optional<ToolDefinition> definition = toolDefinitionRepo.findByRefName(realm, toolRef);

        if (definition.isPresent()) {
            ToolDefinition def = definition.get();
            if (!def.isEnabled()) {
                return errorResult(toolRef, ToolResult.ToolResultStatus.ERROR, "Tool is disabled", start);
            }
            ToolType type = def.getToolType();
            if (type == ToolType.QUANTUM_QUERY || type == ToolType.QUANTUM_API) {
                String gatewayTool = toolRef;
                Map<String, Object> gatewayParams = parameters != null ? new java.util.HashMap<>(parameters) : new java.util.HashMap<>();
                gatewayParams.put("realm", realm);
                if (toolRef.startsWith("search_") && toolRef.length() > 7) {
                    String rootType = toolRef.substring(7);
                    gatewayTool = "query_find";
                    gatewayParams.put("rootType", rootType);
                    if (!gatewayParams.containsKey("query")) {
                        gatewayParams.put("query", "{}");
                    }
                } else if (toolRef.startsWith("get_") && toolRef.length() > 4) {
                    String rootType = toolRef.substring(4);
                    gatewayTool = "query_find";
                    gatewayParams.put("rootType", rootType);
                    Object id = parameters != null ? parameters.get("id") : null;
                    gatewayParams.put("query", id != null ? "_id:\"" + id + "\"" : "{}");
                    if (!gatewayParams.containsKey("page")) {
                        gatewayParams.put("page", Map.of("limit", 1, "skip", 0));
                    }
                } else if (toolRef.startsWith("count_") && toolRef.length() > 6) {
                    String rootType = toolRef.substring(6);
                    gatewayTool = "query_find";
                    gatewayParams.put("rootType", rootType);
                    if (!gatewayParams.containsKey("query")) {
                        gatewayParams.put("query", "{}");
                    }
                    gatewayParams.put("page", Map.of("limit", 0, "skip", 0));
                } else if (toolRef.startsWith("create_") && toolRef.length() > 7) {
                    String rootType = toolRef.substring(7);
                    gatewayTool = "query_save";
                    gatewayParams.put("rootType", rootType);
                    if (!gatewayParams.containsKey("entity") && parameters != null && parameters.containsKey("entity")) {
                        gatewayParams.put("entity", parameters.get("entity"));
                    }
                } else if (toolRef.startsWith("update_") && toolRef.length() > 7) {
                    String rootType = toolRef.substring(7);
                    gatewayTool = "query_save";
                    gatewayParams.put("rootType", rootType);
                    if (parameters != null) {
                        if (parameters.containsKey("entity")) {
                            gatewayParams.put("entity", parameters.get("entity"));
                        }
                        if (parameters.containsKey("id")) {
                            gatewayParams.put("id", parameters.get("id"));
                        }
                    }
                } else if (toolRef.startsWith("delete_") && toolRef.length() > 7) {
                    String rootType = toolRef.substring(7);
                    gatewayTool = "query_delete";
                    gatewayParams.put("rootType", rootType);
                    if (parameters != null && parameters.containsKey("id")) {
                        gatewayParams.put("id", parameters.get("id"));
                    }
                }
                return invokeGateway(gatewayTool, gatewayParams, realm, start);
            }
            if (type == ToolType.EXTERNAL_REST) {
                return invokeExternalRest(def, parameters, realm, start);
            }
            if (type == ToolType.EXTERNAL_MCP) {
                return invokeExternalMcp(def, parameters, realm, start);
            }
            return errorResult(toolRef, ToolResult.ToolResultStatus.ERROR,
                "Tool type " + type + " not yet supported by executor", start);
        }

        if (GATEWAY_TOOL_NAMES.contains(toolRef)) {
            return invokeGateway(toolRef, parameters, realm, start);
        }

        return errorResult(toolRef, ToolResult.ToolResultStatus.ERROR, "Unknown tool: " + toolRef, start);
    }

    private ToolResult invokeGateway(String toolRef, Map<String, Object> parameters, String realm, long startMs) {
        GatewayInvocationResult result = agentExecuteHandler.invokeGatewayTool(toolRef, parameters != null ? parameters : Map.of(), realm);
        long durationMs = System.currentTimeMillis() - startMs;

        ToolResult tr = new ToolResult();
        tr.setToolRef(toolRef);
        tr.setDurationMs(durationMs);
        tr.setHttpStatus(result.getStatusCode());

        if (result.getStatusCode() >= 200 && result.getStatusCode() < 300) {
            tr.setStatus(ToolResult.ToolResultStatus.SUCCESS);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = result.getEntity() instanceof Map
                ? (Map<String, Object>) result.getEntity()
                : Collections.singletonMap("value", result.getEntity());
            tr.setData(data);
        } else {
            tr.setStatus(ToolResult.ToolResultStatus.ERROR);
            tr.setErrorMessage(result.getErrorMessage());
        }
        return tr;
    }

    private ToolResult invokeExternalRest(ToolDefinition def, Map<String, Object> parameters, String realm, long startMs) {
        String providerRef = def.getProviderRef();
        if (providerRef == null || providerRef.isBlank()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "EXTERNAL_REST tool requires providerRef", startMs);
        }
        Optional<ToolProviderConfig> optProvider = toolProviderConfigRepo.findByRefName(realm, providerRef);
        if (optProvider.isEmpty()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "ToolProviderConfig not found: " + providerRef, startMs);
        }
        ToolProviderConfig provider = optProvider.get();
        if (!provider.isEnabled()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "Tool provider is disabled", startMs);
        }
        InvocationConfig invocation = def.getInvocation() != null ? def.getInvocation() : new InvocationConfig();
        ExternalRestInvoker.ExternalRestResult result = externalRestInvoker.invoke(provider, invocation, parameters != null ? parameters : Map.of());
        long durationMs = System.currentTimeMillis() - startMs;
        ToolResult tr = new ToolResult();
        tr.setToolRef(def.getRefName());
        tr.setDurationMs(durationMs);
        tr.setHttpStatus(result.statusCode);
        if (result.statusCode >= 200 && result.statusCode < 300) {
            tr.setStatus(ToolResult.ToolResultStatus.SUCCESS);
            tr.setData(result.data != null ? result.data : Map.of());
        } else {
            tr.setStatus(ToolResult.ToolResultStatus.ERROR);
            tr.setErrorMessage(result.errorMessage != null ? result.errorMessage : "HTTP " + result.statusCode);
        }
        return tr;
    }

    private ToolResult invokeExternalMcp(ToolDefinition def, Map<String, Object> parameters, String realm, long startMs) {
        String providerRef = def.getProviderRef();
        if (providerRef == null || providerRef.isBlank()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "EXTERNAL_MCP tool requires providerRef", startMs);
        }
        Optional<ToolProviderConfig> optProvider = toolProviderConfigRepo.findByRefName(realm, providerRef);
        if (optProvider.isEmpty()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "ToolProviderConfig not found: " + providerRef, startMs);
        }
        ToolProviderConfig provider = optProvider.get();
        if (!provider.isEnabled()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "Tool provider is disabled", startMs);
        }
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return errorResult(def.getRefName(), ToolResult.ToolResultStatus.ERROR, "Tool provider has no baseUrl", startMs);
        }
        java.util.Map<String, String> headers = provider.getDefaultHeaders() != null ? provider.getDefaultHeaders() : Map.of();
        McpClient.McpCallResult result = mcpClient.callTool(baseUrl, def.getRefName(), parameters != null ? parameters : Map.of(), headers);
        long durationMs = System.currentTimeMillis() - startMs;
        ToolResult tr = new ToolResult();
        tr.setToolRef(def.getRefName());
        tr.setDurationMs(durationMs);
        if (result.isError()) {
            tr.setStatus(ToolResult.ToolResultStatus.ERROR);
            tr.setErrorMessage(result.errorMessage() != null ? result.errorMessage() : "MCP call failed");
        } else {
            tr.setStatus(ToolResult.ToolResultStatus.SUCCESS);
            tr.setData(java.util.Map.of("content", result.content() != null ? result.content() : List.of()));
        }
        return tr;
    }

    private static ToolResult errorResult(String toolRef, ToolResult.ToolResultStatus status, String message, long startMs) {
        ToolResult tr = new ToolResult();
        tr.setToolRef(toolRef);
        tr.setStatus(status);
        tr.setErrorMessage(message);
        tr.setDurationMs(System.currentTimeMillis() - startMs);
        return tr;
    }
}
