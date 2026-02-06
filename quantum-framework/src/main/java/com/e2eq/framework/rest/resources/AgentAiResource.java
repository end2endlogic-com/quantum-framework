package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.agent.AgentExecutionRequest;
import com.e2eq.framework.api.agent.AgentExecutionResult;
import com.e2eq.framework.api.agent.AgentExecutionService;
import com.e2eq.framework.api.agent.ToolResolver;
import com.e2eq.framework.api.tools.ExecutionContext;
import com.e2eq.framework.api.tools.ToolExecutor;
import com.e2eq.framework.api.tools.ToolResult;
import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.morphia.AgentRepo;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST resource for agent-scoped AI operations under /api/v1/ai/agents.
 * Provides get-by-refName, resolved tools for an agent, and single-shot execute
 * (tool call in the context of that agent).
 */
@Path("/api/v1/ai/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
@FunctionalMapping(area = "ai", domain = "agents")
public class AgentAiResource {

    @Inject
    AgentRepo agentRepo;

    @Inject
    ToolResolver toolResolver;

    @Inject
    ToolExecutor toolExecutor;

    @Inject
    AgentExecutionService agentExecutionService;

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

    /**
     * Gets an agent by refName in the current realm.
     */
    @GET
    @Path("{refName}")
    @Operation(summary = "Get agent by refName")
    @FunctionalAction("get")
    @SecurityRequirement(name = "bearerAuth")
    public Response getByRefName(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (refName == null || refName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<Agent> opt = agentRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(AgentConfigResource.toResponse(opt.get())).build();
    }

    /**
     * Returns the list of tools resolved for this agent (explicit refs, categories, tags,
     * minus exclusions and disabled, trimmed to maxToolsInContext).
     */
    @GET
    @Path("{refName}/tools")
    @Operation(summary = "List tools for agent")
    @FunctionalAction("listTools")
    @SecurityRequirement(name = "bearerAuth")
    public Response listTools(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (refName == null || refName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<Agent> opt = agentRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<ToolDefinition> resolved = toolResolver.resolveToolsForAgent(opt.get(), realm);
        List<ToolResource.ToolDefinitionResponse> out = resolved.stream()
            .map(ToolResource::toResponse)
            .toList();
        return Response.ok(new ToolResource.ToolListResponse(out, out.size())).build();
    }

    /**
     * Execute: full agent loop (if userMessage present) or single-shot tool (if tool/name present).
     * Full loop: request body { userMessage, conversationId?, context? }; realm from query or context.
     * Single-shot: request body { tool or name, arguments or params }.
     */
    @POST
    @Path("{refName}/execute")
    @Operation(summary = "Execute agent or single tool")
    @FunctionalAction("execute")
    @SecurityRequirement(name = "bearerAuth")
    public Response execute(
        @PathParam("refName") String refName,
        AgentExecuteRequestBody request,
        @QueryParam("realm") String realmParam
    ) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (refName == null || refName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<Agent> opt = agentRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (request != null && request.userMessage != null && !request.userMessage.isBlank()) {
            AgentExecutionRequest execReq = new AgentExecutionRequest();
            execReq.setAgentRef(refName);
            execReq.setUserMessage(request.userMessage);
            execReq.setConversationId(request.conversationId);
            execReq.setContext(request.context);
            execReq.setRealmId(realm);
            execReq.setPrincipalId(request.principalId);
            AgentExecutionResult result = agentExecutionService.execute(execReq);
            return Response.ok(result).build();
        }

        String tool = request != null && request.tool != null ? request.tool : (request != null ? request.name : null);
        if (tool == null || tool.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "tool/name or userMessage required")).build();
        }
        Map<String, Object> arguments = request.arguments != null ? request.arguments : (request.params != null ? request.params : Map.of());

        ExecutionContext ctx = new ExecutionContext();
        ctx.setRealmId(realm);
        ctx.setAgentRef(refName);

        ToolResult result = toolExecutor.execute(tool, arguments, ctx);
        if (result.getStatus() == ToolResult.ToolResultStatus.SUCCESS) {
            return Response.ok(result.getData()).build();
        }
        int status = result.getHttpStatus() != null ? result.getHttpStatus() : 500;
        return Response.status(status)
            .entity(Map.of("error", result.getErrorMessage() != null ? result.getErrorMessage() : result.getStatus().name()))
            .build();
    }

    /** Request body for POST /api/v1/ai/agents/{refName}/execute: full loop (userMessage) or single-shot (tool, arguments). */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class AgentExecuteRequestBody {
        /** For full loop: user message. */
        public String userMessage;
        /** For full loop: existing conversation id. */
        public String conversationId;
        /** For full loop: context variables. */
        public Map<String, Object> context;
        /** For full loop: principal (defaults to current). */
        public String principalId;
        /** For single-shot: tool ref name. */
        public String tool;
        /** Alternative field name for tool. */
        public String name;
        /** For single-shot: arguments. */
        public Map<String, Object> arguments;
        /** Alternative field name for arguments. */
        public Map<String, Object> params;
    }
}
