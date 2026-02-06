package com.e2eq.framework.api.tools;

import com.e2eq.framework.api.tools.GatewayToolSeeder;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Exposes the framework tool registry for MCP-style access: list tools and call tools.
 * External MCP clients can use this (or REST that delegates to it) to list and invoke
 * tools without implementing the full MCP transport.
 */
@ApplicationScoped
public class QuantumMCPServer {

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    @Inject
    GatewayToolSeeder gatewayToolSeeder;

    @Inject
    ToolExecutor toolExecutor;

    /**
     * Lists all tools in the realm (ensures gateway tools are seeded). Suitable for MCP list_tools.
     *
     * @param realm realm id
     * @return list of tool definitions
     */
    public List<ToolDefinition> listTools(String realm) {
        if (realm == null || realm.isBlank()) {
            return List.of();
        }
        gatewayToolSeeder.seedRealm(realm);
        return toolDefinitionRepo.list(realm);
    }

    /**
     * Invokes a tool by refName. Suitable for MCP call_tool.
     *
     * @param realm     realm id
     * @param toolRef   tool ref name
     * @param arguments tool arguments
     * @param context   execution context (optional; realm and agentRef can be set)
     * @return tool result
     */
    public ToolResult callTool(String realm, String toolRef, Map<String, Object> arguments, ExecutionContext context) {
        if (realm == null || realm.isBlank() || toolRef == null || toolRef.isBlank()) {
            ToolResult tr = new ToolResult();
            tr.setToolRef(toolRef);
            tr.setStatus(ToolResult.ToolResultStatus.VALIDATION_ERROR);
            tr.setErrorMessage("realm and toolRef are required");
            return tr;
        }
        ExecutionContext ctx = context != null ? context : new ExecutionContext();
        if (ctx.getRealmId() == null || ctx.getRealmId().isBlank()) {
            ctx.setRealmId(realm);
        }
        return toolExecutor.execute(toolRef, arguments != null ? arguments : Map.of(), ctx);
    }
}
