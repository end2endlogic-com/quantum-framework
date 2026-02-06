package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.tools.ExecutionContext;
import com.e2eq.framework.api.tools.GatewayToolSeeder;
import com.e2eq.framework.api.tools.ToolExecutor;
import com.e2eq.framework.api.tools.ToolResult;
import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.securityrules.RuleContext;
import io.quarkus.runtime.annotations.RegisterForReflection;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST resource for Tool Definition CRUD and execute under /api/v1/ai/tools.
 * Tools are realm-scoped; realm is derived from security context.
 */
@Path("/api/v1/ai/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
@FunctionalMapping(area = "ai", domain = "tools")
public class ToolResource {

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    @Inject
    ToolExecutor toolExecutor;

    @Inject
    GatewayToolSeeder gatewayToolSeeder;

    @Inject
    com.e2eq.framework.api.tools.ToolAutoGenerator toolAutoGenerator;

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

    /** Package-visible for use by AgentAiResource when returning resolved tools. */
    static ToolDefinitionResponse toResponse(ToolDefinition t) {
        if (t == null) return null;
        ToolDefinitionResponse r = new ToolDefinitionResponse();
        r.id = t.getId() != null ? t.getId().toHexString() : null;
        r.refName = t.getRefName();
        r.name = t.getName();
        r.description = t.getDescription();
        r.category = t.getCategory();
        r.toolType = t.getToolType();
        r.hasSideEffects = t.isHasSideEffects();
        r.enabled = t.isEnabled();
        r.source = t.getSource();
        return r;
    }

    /**
     * Lists all tools in the current realm. Ensures gateway tools are seeded first.
     */
    @GET
    @Operation(summary = "List tools")
    @FunctionalAction("list")
    @SecurityRequirement(name = "bearerAuth")
    public Response list(@QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        gatewayToolSeeder.seedRealm(realm);
        List<ToolDefinition> tools = toolDefinitionRepo.list(realm);
        List<ToolDefinitionResponse> out = tools.stream().map(ToolResource::toResponse).toList();
        return Response.ok(new ToolListResponse(out, out.size())).build();
    }

    /**
     * Gets a tool by refName.
     */
    @GET
    @Path("{refName}")
    @Operation(summary = "Get tool by refName")
    @FunctionalAction("read")
    @SecurityRequirement(name = "bearerAuth")
    public Response getByRefName(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<ToolDefinition> opt = toolDefinitionRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(toResponse(opt.get())).build();
    }

    /**
     * Creates a tool in the current realm.
     */
    @POST
    @Operation(summary = "Create tool")
    @FunctionalAction("create")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(ToolDefinition body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.getRefName() == null || body.getRefName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (toolDefinitionRepo.findByRefName(realm, body.getRefName()).isPresent()) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        if (body.getSource() == null || body.getSource().isBlank()) {
            body.setSource("manual");
        }
        ToolDefinition saved = toolDefinitionRepo.save(realm, body);
        return Response.status(Response.Status.CREATED).entity(toResponse(saved)).build();
    }

    /**
     * Updates a tool by refName.
     */
    @PUT
    @Path("{refName}")
    @Operation(summary = "Update tool")
    @FunctionalAction("update")
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("refName") String refName, ToolDefinition body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<ToolDefinition> opt = toolDefinitionRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ToolDefinition existing = opt.get();
        if (body.getName() != null) existing.setName(body.getName());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getCategory() != null) existing.setCategory(body.getCategory());
        if (body.getTags() != null) existing.setTags(body.getTags());
        if (body.getToolType() != null) existing.setToolType(body.getToolType());
        if (body.getInvocation() != null) existing.setInvocation(body.getInvocation());
        if (body.getProviderRef() != null) existing.setProviderRef(body.getProviderRef());
        existing.setHasSideEffects(body.isHasSideEffects());
        existing.setIdempotent(body.isIdempotent());
        if (body.getEstimatedLatency() != null) existing.setEstimatedLatency(body.getEstimatedLatency());
        if (body.getCostHint() != null) existing.setCostHint(body.getCostHint());
        existing.setRequiresConfirmation(body.isRequiresConfirmation());
        if (body.getAvailableAs() != null) existing.setAvailableAs(body.getAvailableAs());
        existing.setEnabled(body.isEnabled());
        if (body.getSecurityUri() != null) existing.setSecurityUri(body.getSecurityUri());
        if (body.getLongDescription() != null) existing.setLongDescription(body.getLongDescription());
        if (body.getExamples() != null) existing.setExamples(body.getExamples());
        toolDefinitionRepo.save(realm, existing);
        return Response.ok(toResponse(existing)).build();
    }

    /**
     * Deletes a tool by refName.
     */
    @DELETE
    @Path("{refName}")
    @Operation(summary = "Delete tool")
    @FunctionalAction("delete")
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<ToolDefinition> opt = toolDefinitionRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        toolDefinitionRepo.deleteById(realm, opt.get().getId().toHexString());
        return Response.noContent().build();
    }

    /**
     * Counts tools in the realm.
     */
    @GET
    @Path("count")
    @Operation(summary = "Count tools")
    @FunctionalAction("count")
    @SecurityRequirement(name = "bearerAuth")
    public Response count(@QueryParam("realm") String realmParam, @QueryParam("enabled") Boolean enabled) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        long count = toolDefinitionRepo.count(realm, enabled);
        return Response.ok(Map.of("count", count)).build();
    }

    /**
     * Executes a tool by refName with the given parameters.
     */
    @POST
    @Path("{refName}/execute")
    @Operation(summary = "Execute tool")
    @FunctionalAction("execute")
    @SecurityRequirement(name = "bearerAuth")
    public Response execute(@PathParam("refName") String refName, Map<String, Object> parameters, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        ExecutionContext ctx = new ExecutionContext();
        ctx.setRealmId(realm);
        ToolResult result = toolExecutor.execute(refName, parameters != null ? parameters : Map.of(), ctx);
        if (result.getStatus() == ToolResult.ToolResultStatus.SUCCESS) {
            return Response.ok(result.getData()).build();
        }
        int status = result.getHttpStatus() != null ? result.getHttpStatus() : 500;
        return Response.status(status).entity(Map.of("error", result.getErrorMessage() != null ? result.getErrorMessage() : result.getStatus().name())).build();
    }

    /**
     * Validates input against the tool's input schema (dry run). Phase 1: returns 200 if tool exists.
     */
    @POST
    @Path("{refName}/validate")
    @Operation(summary = "Validate tool input")
    @FunctionalAction("validate")
    @SecurityRequirement(name = "bearerAuth")
    public Response validate(@PathParam("refName") String refName, Map<String, Object> parameters, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<ToolDefinition> opt = toolDefinitionRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("valid", true, "toolRef", refName)).build();
    }

    /**
     * Generates tools from root type names (e.g. search_Order, get_Order, create_Order for rootType Order).
     * Body: { "rootTypes": ["Order", "Customer"], "persist": false, "excludeOperations": ["delete"] }. Returns generated tools.
     */
    @POST
    @Path("generate")
    @Operation(summary = "Generate tools from root types")
    @FunctionalAction("generate")
    @SecurityRequirement(name = "bearerAuth")
    public Response generate(GenerateToolsRequest body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.rootTypes == null || body.rootTypes.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "rootTypes required")).build();
        }
        boolean persist = body.persist != null && body.persist;
        java.util.Set<String> exclude = body.excludeOperations != null
            ? new java.util.HashSet<>(body.excludeOperations)
            : java.util.Set.of();
        List<ToolDefinition> generated = toolAutoGenerator.generateFullCrudForRootTypes(realm, body.rootTypes, persist, exclude);
        List<ToolDefinitionResponse> out = generated.stream().map(ToolResource::toResponse).toList();
        return Response.ok(Map.of("tools", out, "count", out.size(), "persisted", persist)).build();
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class GenerateToolsRequest {
        public List<String> rootTypes;
        public Boolean persist;
        /** Operations to skip: search, get, create, update, delete, count. */
        public List<String> excludeOperations;
    }

    @RegisterForReflection
    public static class ToolListResponse {
        public List<ToolDefinitionResponse> tools;
        public int count;

        public ToolListResponse(List<ToolDefinitionResponse> tools, int count) {
            this.tools = tools;
            this.count = count;
        }
    }

    @RegisterForReflection
    public static class ToolDefinitionResponse {
        public String id;
        public String refName;
        public String name;
        public String description;
        public String category;
        public com.e2eq.framework.model.persistent.tools.ToolType toolType;
        public boolean hasSideEffects;
        public boolean enabled;
        public String source;
    }
}
