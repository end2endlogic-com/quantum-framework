package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.tools.QuantumMCPServer;
import com.e2eq.framework.api.tools.ToolExecutor;
import com.e2eq.framework.api.tools.ToolResult;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.securityrules.RuleContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST bridge for MCP-style access: list tools and call tool.
 * GET /api/v1/ai/mcp/tools, POST /api/v1/ai/mcp/call.
 */
@Path("/api/v1/ai/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
@FunctionalMapping(area = "ai", domain = "mcp")
public class McpBridgeResource {

    @Inject
    QuantumMCPServer quantumMCPServer;

    @Inject
    RuleContext ruleContext;

    private String getRealmId() {
        Optional<PrincipalContext> pc = SecurityContext.getPrincipalContext();
        Optional<ResourceContext> rc = SecurityContext.getResourceContext();
        if (pc.isEmpty() || rc.isEmpty()) {
            return null;
        }
        return ruleContext.getRealmId(pc.get(), rc.get());
    }

    @GET
    @Path("tools")
    @Operation(summary = "List tools (MCP-style)")
    @FunctionalAction("listTools")
    @SecurityRequirement(name = "bearerAuth")
    public Response listTools(@QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        List<ToolDefinition> tools = quantumMCPServer.listTools(realm);
        List<Map<String, Object>> out = tools.stream()
            .map(t -> Map.<String, Object>of(
                "refName", t.getRefName() != null ? t.getRefName() : "",
                "name", t.getName() != null ? t.getName() : t.getRefName(),
                "description", t.getDescription() != null ? t.getDescription() : ""
            ))
            .collect(Collectors.toList());
        return Response.ok(Map.of("tools", out)).build();
    }

    @POST
    @Path("call")
    @Operation(summary = "Call tool (MCP-style)")
    @FunctionalAction("callTool")
    @SecurityRequirement(name = "bearerAuth")
    public Response call(McpCallRequest body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.toolRef == null || body.toolRef.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "toolRef required")).build();
        }
        ToolResult result = quantumMCPServer.callTool(realm, body.toolRef, body.arguments, null);
        if (result.getStatus() == ToolResult.ToolResultStatus.SUCCESS) {
            return Response.ok(result.getData()).build();
        }
        return Response.status(result.getHttpStatus() != null ? result.getHttpStatus() : 500)
            .entity(Map.of("error", result.getErrorMessage() != null ? result.getErrorMessage() : result.getStatus().name()))
            .build();
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class McpCallRequest {
        public String toolRef;
        public Map<String, Object> arguments;
    }
}
