package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.agent.PromptStep;
import com.e2eq.framework.model.persistent.morphia.AgentRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.dto.AgentCreateUpdateRequest;
import com.e2eq.framework.rest.dto.AgentResponse;
import com.e2eq.framework.rest.dto.PromptStepDto;
import com.e2eq.framework.securityrules.RuleContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
import java.util.Optional;

/**
 * REST resource for Agent (LLM + context) configuration. CRUD under /api/agents.
 * Agents are stored per realm; realm is derived from security context.
 */
@Path("/api/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
public class AgentConfigResource {

    @Inject
    AgentRepo agentRepo;

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

    /** Package-visible for use by AgentAiResource when returning agent by refName. */
    static AgentResponse toResponse(Agent a) {
        if (a == null) return null;
        AgentResponse r = new AgentResponse();
        r.id = a.getId() != null ? a.getId().toHexString() : null;
        r.refName = a.getRefName();
        r.name = a.getName();
        r.description = a.getDescription();
        r.llmConfigRef = a.getLlmConfigRef();
        r.context = new ArrayList<>();
        if (a.getContext() != null) {
            for (PromptStep s : a.getContext()) {
                r.context.add(new PromptStepDto(
                    s.getOrder(),
                    s.getRole() != null ? s.getRole() : "system",
                    s.getContent()));
            }
            r.context.sort((x, y) -> Integer.compare(x.order, y.order));
        }
        r.toolRefs = a.getToolRefs();
        r.toolCategories = a.getToolCategories();
        r.toolTags = a.getToolTags();
        r.excludedToolRefs = a.getExcludedToolRefs();
        r.maxToolsInContext = a.getMaxToolsInContext();
        r.delegateAgentRefs = a.getDelegateAgentRefs();
        r.responseFormat = a.getResponseFormat();
        r.securityUri = a.getSecurityUri();
        r.principalRef = a.getPrincipalRef();
        r.allowedRealms = a.getAllowedRealms();
        r.requiresApproval = a.isRequiresApproval();
        r.enabled = a.getEnabled();
        r.enabledTools = a.getEnabledTools();
        return r;
    }

    private static List<PromptStep> fromDtoList(List<PromptStepDto> dtos) {
        if (dtos == null) return new ArrayList<>();
        List<PromptStep> list = new ArrayList<>();
        for (PromptStepDto d : dtos) {
            PromptStep s = new PromptStep();
            s.setOrder(d.order);
            s.setRole(d.role != null && !d.role.isBlank() ? d.role : "system");
            s.setContent(d.content);
            list.add(s);
        }
        list.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        return list;
    }

    /**
     * Lists all agents in the current realm.
     */
    @GET
    @Operation(summary = "List agents")
    @SecurityRequirement(name = "bearerAuth")
    public Response list() {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        List<Agent> agents = agentRepo.list(realm);
        List<AgentResponse> out = agents.stream().map(AgentConfigResource::toResponse).toList();
        return Response.ok(out).build();
    }

    /**
     * Gets an agent by id.
     */
    @GET
    @Path("{id}")
    @Operation(summary = "Get agent by id")
    @SecurityRequirement(name = "bearerAuth")
    public Response getById(@PathParam("id") String id) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<Agent> opt = agentRepo.findById(realm, id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(toResponse(opt.get())).build();
    }

    /**
     * Gets an agent by refName (e.g. ?refName=OBLIGATIONS_SUGGEST).
     */
    @GET
    @Path("by-ref")
    @Operation(summary = "Get agent by refName")
    @SecurityRequirement(name = "bearerAuth")
    public Response getByRefName(@QueryParam("refName") String refName) {
        String realm = getRealmId();
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
        return Response.ok(toResponse(opt.get())).build();
    }

    /**
     * Creates an agent in the current realm.
     */
    @POST
    @Operation(summary = "Create agent")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(AgentCreateUpdateRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (request == null || request.refName == null || request.refName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (agentRepo.findByRefName(realm, request.refName).isPresent()) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        Agent agent = new Agent();
        agent.setRefName(request.refName.trim());
        agent.setName(request.name != null ? request.name.trim() : null);
        agent.setDescription(request.description != null && !request.description.isBlank() ? request.description.trim() : null);
        agent.setLlmConfigRef(request.llmConfigRef != null && !request.llmConfigRef.isBlank() ? request.llmConfigRef.trim() : null);
        agent.setContext(fromDtoList(request.context));
        agent.setToolRefs(request.toolRefs);
        agent.setToolCategories(request.toolCategories);
        agent.setToolTags(request.toolTags);
        agent.setExcludedToolRefs(request.excludedToolRefs);
        agent.setMaxToolsInContext(request.maxToolsInContext);
        agent.setDelegateAgentRefs(request.delegateAgentRefs);
        agent.setResponseFormat(request.responseFormat != null && !request.responseFormat.isBlank() ? request.responseFormat.trim() : null);
        agent.setSecurityUri(request.securityUri != null && !request.securityUri.isBlank() ? request.securityUri.trim() : null);
        agent.setPrincipalRef(request.principalRef != null && !request.principalRef.isBlank() ? request.principalRef.trim() : null);
        agent.setAllowedRealms(request.allowedRealms);
        agent.setRequiresApproval(request.requiresApproval);
        agent.setEnabled(request.enabled);
        agent.setEnabledTools(request.enabledTools);
        agentRepo.save(realm, agent);
        return Response.status(Response.Status.CREATED).entity(toResponse(agent)).build();
    }

    /**
     * Updates an agent by id.
     */
    @PUT
    @Path("{id}")
    @Operation(summary = "Update agent")
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("id") String id, AgentCreateUpdateRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<Agent> opt = agentRepo.findById(realm, id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Agent agent = opt.get();
        if (request.refName != null && !request.refName.isBlank()) {
            Optional<Agent> existingRef = agentRepo.findByRefName(realm, request.refName.trim());
            if (existingRef.isPresent() && !existingRef.get().getId().equals(agent.getId())) {
                return Response.status(Response.Status.CONFLICT).build();
            }
            agent.setRefName(request.refName.trim());
        }
        if (request.name != null) agent.setName(request.name.trim());
        if (request.description != null) agent.setDescription(request.description.isBlank() ? null : request.description.trim());
        if (request.llmConfigRef != null) agent.setLlmConfigRef(request.llmConfigRef.isBlank() ? null : request.llmConfigRef.trim());
        if (request.context != null) agent.setContext(fromDtoList(request.context));
        if (request.toolRefs != null) agent.setToolRefs(request.toolRefs);
        if (request.toolCategories != null) agent.setToolCategories(request.toolCategories);
        if (request.toolTags != null) agent.setToolTags(request.toolTags);
        if (request.excludedToolRefs != null) agent.setExcludedToolRefs(request.excludedToolRefs);
        agent.setMaxToolsInContext(request.maxToolsInContext);
        if (request.delegateAgentRefs != null) agent.setDelegateAgentRefs(request.delegateAgentRefs);
        if (request.responseFormat != null) agent.setResponseFormat(request.responseFormat.isBlank() ? null : request.responseFormat.trim());
        if (request.securityUri != null) agent.setSecurityUri(request.securityUri.isBlank() ? null : request.securityUri.trim());
        if (request.principalRef != null) agent.setPrincipalRef(request.principalRef.isBlank() ? null : request.principalRef.trim());
        if (request.allowedRealms != null) agent.setAllowedRealms(request.allowedRealms);
        agent.setRequiresApproval(request.requiresApproval);
        if (request.enabled != null) agent.setEnabled(request.enabled);
        if (request.enabledTools != null) agent.setEnabledTools(request.enabledTools);
        agentRepo.save(realm, agent);
        return Response.ok(toResponse(agent)).build();
    }

    /**
     * Deletes an agent by id.
     */
    @DELETE
    @Path("{id}")
    @Operation(summary = "Delete agent")
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("id") String id) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!agentRepo.deleteById(realm, id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }
}
