package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.agent.AgentExecutionRequest;
import com.e2eq.framework.api.agent.AgentExecutionResult;
import com.e2eq.framework.api.agent.AgentExecutionService;
import com.e2eq.framework.model.persistent.agent.AgentConversation;
import com.e2eq.framework.model.persistent.agent.AgentConversationTurn;
import com.e2eq.framework.model.persistent.morphia.AgentConversationRepo;
import com.e2eq.framework.model.persistent.morphia.AgentConversationTurnRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.securityrules.RuleContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST resource for agent conversations at /api/v1/ai/conversations.
 * List, get, create, delete conversations; continue conversation with a new message.
 */
@Path("/api/v1/ai/conversations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
@FunctionalMapping(area = "ai", domain = "conversations")
public class ConversationResource {

    @Inject
    AgentConversationRepo conversationRepo;
    @Inject
    AgentConversationTurnRepo turnRepo;
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
     * Lists conversations for an agent (and optional principal) in the realm.
     */
    @GET
    @Operation(summary = "List conversations")
    @FunctionalAction("list")
    @SecurityRequirement(name = "bearerAuth")
    public Response list(
        @QueryParam("agentRef") String agentRef,
        @QueryParam("principalId") String principalId,
        @QueryParam("realm") String realmParam
    ) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (agentRef == null || agentRef.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "agentRef required")).build();
        }
        List<AgentConversation> list = conversationRepo.list(realm, agentRef, principalId);
        List<ConversationSummary> out = list.stream()
            .map(c -> new ConversationSummary(
                c.getId() != null ? c.getId().toHexString() : null,
                c.getAgentRef(),
                c.getPrincipalId(),
                c.getTitle(),
                c.getStatus(),
                c.getTurnCount()
            ))
            .toList();
        return Response.ok(Map.of("conversations", out, "count", out.size())).build();
    }

    /**
     * Gets a conversation by id with its turns.
     */
    @GET
    @Path("{id}")
    @Operation(summary = "Get conversation")
    @FunctionalAction("get")
    @SecurityRequirement(name = "bearerAuth")
    public Response getById(@PathParam("id") String id, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<AgentConversation> opt = conversationRepo.findById(realm, id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        AgentConversation c = opt.get();
        List<AgentConversationTurn> turns = turnRepo.listByConversationId(realm, id);
        ConversationDetail detail = new ConversationDetail(
            c.getId() != null ? c.getId().toHexString() : null,
            c.getAgentRef(),
            c.getPrincipalId(),
            c.getTitle(),
            c.getStatus(),
            c.getTurnCount(),
            c.getTotalTokensUsed(),
            turns.stream().map(this::toTurnSummary).toList()
        );
        return Response.ok(detail).build();
    }

    /**
     * Creates a new conversation (optional title). Returns conversation id; send userMessage via continue.
     */
    @POST
    @Operation(summary = "Create conversation")
    @FunctionalAction("create")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(CreateConversationRequest body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.agentRef == null || body.agentRef.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "agentRef required")).build();
        }
        AgentConversation c = new AgentConversation();
        c.setAgentRef(body.agentRef);
        c.setPrincipalId(body.principalId != null ? body.principalId : "anonymous");
        c.setTitle(body.title != null ? body.title : "New conversation");
        c.setStatus("ACTIVE");
        c.setTurnCount(0);
        c = conversationRepo.save(realm, c);
        return Response.status(Response.Status.CREATED).entity(Map.of("id", c.getId().toHexString(), "conversationId", c.getId().toHexString())).build();
    }

    /**
     * Deletes a conversation and its turns.
     */
    @DELETE
    @Path("{id}")
    @Operation(summary = "Delete conversation")
    @FunctionalAction("delete")
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("id") String id, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        turnRepo.deleteByConversationId(realm, id);
        if (!conversationRepo.deleteById(realm, id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    /**
     * Continues a conversation with a new user message; runs agent loop and returns result.
     */
    @POST
    @Path("{id}/continue")
    @Operation(summary = "Continue conversation")
    @FunctionalAction("continue")
    @SecurityRequirement(name = "bearerAuth")
    public Response continueConversation(
        @PathParam("id") String id,
        ContinueRequest body,
        @QueryParam("realm") String realmParam
    ) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.userMessage == null || body.userMessage.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "userMessage required")).build();
        }
        AgentExecutionResult result = agentExecutionService.continueConversation(realm, id, body.userMessage, body.principalId);
        return Response.ok(result).build();
    }

    private TurnSummary toTurnSummary(AgentConversationTurn t) {
        return new TurnSummary(
            t.getTurnIndex(),
            t.getRole(),
            t.getContent(),
            t.getTokensUsed(),
            t.getDurationMs()
        );
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ConversationSummary {
        public String id;
        public String agentRef;
        public String principalId;
        public String title;
        public String status;
        public int turnCount;

        public ConversationSummary(String id, String agentRef, String principalId, String title, String status, int turnCount) {
            this.id = id;
            this.agentRef = agentRef;
            this.principalId = principalId;
            this.title = title;
            this.status = status;
            this.turnCount = turnCount;
        }
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ConversationDetail {
        public String id;
        public String agentRef;
        public String principalId;
        public String title;
        public String status;
        public int turnCount;
        public int totalTokensUsed;
        public List<TurnSummary> turns;

        public ConversationDetail(String id, String agentRef, String principalId, String title, String status, int turnCount, int totalTokensUsed, List<TurnSummary> turns) {
            this.id = id;
            this.agentRef = agentRef;
            this.principalId = principalId;
            this.title = title;
            this.status = status;
            this.turnCount = turnCount;
            this.totalTokensUsed = totalTokensUsed;
            this.turns = turns != null ? turns : new ArrayList<>();
        }
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class TurnSummary {
        public int turnIndex;
        public String role;
        public String content;
        public int tokensUsed;
        public long durationMs;

        public TurnSummary(int turnIndex, String role, String content, int tokensUsed, long durationMs) {
            this.turnIndex = turnIndex;
            this.role = role;
            this.content = content;
            this.tokensUsed = tokensUsed;
            this.durationMs = durationMs;
        }
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class CreateConversationRequest {
        public String agentRef;
        public String principalId;
        public String title;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ContinueRequest {
        public String userMessage;
        public String principalId;
    }
}
