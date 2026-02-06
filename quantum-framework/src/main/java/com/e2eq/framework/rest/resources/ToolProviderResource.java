package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.api.tools.MCPToolImporter;
import com.e2eq.framework.model.persistent.morphia.ToolProviderConfigRepo;
import com.e2eq.framework.model.persistent.tools.AuthConfig;
import com.e2eq.framework.model.persistent.tools.ProviderType;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
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
 * REST resource for Tool Provider Config CRUD at /api/v1/ai/tool-providers.
 */
@Path("/api/v1/ai/tool-providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "user", "admin", "system" })
@FunctionalMapping(area = "ai", domain = "toolProviders")
public class ToolProviderResource {

    @Inject
    ToolProviderConfigRepo toolProviderConfigRepo;

    @Inject
    MCPToolImporter mcpToolImporter;

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
    @Operation(summary = "List tool providers")
    @FunctionalAction("list")
    @SecurityRequirement(name = "bearerAuth")
    public Response list(@QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        List<ToolProviderConfig> list = toolProviderConfigRepo.list(realm);
        List<ToolProviderConfigResponse> out = list.stream().map(this::toResponse).toList();
        return Response.ok(Map.of("providers", out, "count", out.size())).build();
    }

    @GET
    @Path("{refName}")
    @Operation(summary = "Get tool provider by refName")
    @FunctionalAction("get")
    @SecurityRequirement(name = "bearerAuth")
    public Response getByRefName(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<ToolProviderConfig> opt = toolProviderConfigRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(toResponse(opt.get())).build();
    }

    @POST
    @Operation(summary = "Create tool provider")
    @FunctionalAction("create")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(ToolProviderConfigCreateUpdateRequest body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null || body.refName == null || body.refName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "refName required")).build();
        }
        if (toolProviderConfigRepo.findByRefName(realm, body.refName.trim()).isPresent()) {
            return Response.status(Response.Status.CONFLICT).build();
        }
        ToolProviderConfig c = fromRequest(body);
        c = toolProviderConfigRepo.save(realm, c);
        return Response.status(Response.Status.CREATED).entity(toResponse(c)).build();
    }

    @PUT
    @Path("{refName}")
    @Operation(summary = "Update tool provider")
    @FunctionalAction("update")
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("refName") String refName, ToolProviderConfigCreateUpdateRequest body, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Optional<ToolProviderConfig> opt = toolProviderConfigRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ToolProviderConfig c = opt.get();
        updateFromRequest(c, body);
        c = toolProviderConfigRepo.save(realm, c);
        return Response.ok(toResponse(c)).build();
    }

    @DELETE
    @Path("{refName}")
    @Operation(summary = "Delete tool provider")
    @FunctionalAction("delete")
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("refName") String refName, @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (!toolProviderConfigRepo.deleteByRefName(realm, refName)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    /**
     * Import tools from an MCP provider. Provider must have providerType MCP and a valid baseUrl.
     * Body: { "persist": true }. Returns list of imported tool refNames.
     */
    @POST
    @Path("{refName}/import")
    @Operation(summary = "Import tools from MCP provider")
    @FunctionalAction("import")
    @SecurityRequirement(name = "bearerAuth")
    public Response importTools(@PathParam("refName") String refName, ImportToolsRequest body,
                                @QueryParam("realm") String realmParam) {
        String realm = realmParam != null && !realmParam.isBlank() ? realmParam : getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        Optional<ToolProviderConfig> opt = toolProviderConfigRepo.findByRefName(realm, refName);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ToolProviderConfig provider = opt.get();
        if (provider.getProviderType() != ProviderType.MCP) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "Import only supported for MCP providers")).build();
        }
        boolean persist = body != null && Boolean.TRUE.equals(body.persist);
        List<ToolDefinition> imported = mcpToolImporter.importFromProvider(realm, provider, persist);
        List<String> refNames = imported.stream().map(ToolDefinition::getRefName).toList();
        return Response.ok(Map.of("tools", refNames, "count", refNames.size(), "persisted", persist)).build();
    }

    private ToolProviderConfigResponse toResponse(ToolProviderConfig c) {
        ToolProviderConfigResponse r = new ToolProviderConfigResponse();
        r.id = c.getId() != null ? c.getId().toHexString() : null;
        r.refName = c.getRefName();
        r.name = c.getName();
        r.providerType = c.getProviderType();
        r.baseUrl = c.getBaseUrl();
        r.timeoutMs = c.getTimeoutMs();
        r.maxRetries = c.getMaxRetries();
        r.enabled = c.isEnabled();
        r.mcpTransport = c.getMcpTransport();
        r.autoDiscoverTools = c.isAutoDiscoverTools();
        r.lastDiscoverySync = c.getLastDiscoverySync();
        return r;
    }

    private static ToolProviderConfig fromRequest(ToolProviderConfigCreateUpdateRequest body) {
        ToolProviderConfig c = new ToolProviderConfig();
        c.setRefName(body.refName.trim());
        c.setName(body.name != null ? body.name.trim() : body.refName);
        c.setProviderType(body.providerType != null ? body.providerType : ProviderType.REST);
        c.setBaseUrl(body.baseUrl != null ? body.baseUrl.trim() : null);
        c.setTimeoutMs(body.timeoutMs > 0 ? body.timeoutMs : 30_000);
        c.setMaxRetries(body.maxRetries >= 0 ? body.maxRetries : 2);
        c.setEnabled(body.enabled != null ? body.enabled : true);
        c.setMcpTransport(body.mcpTransport != null ? body.mcpTransport.trim() : null);
        c.setAutoDiscoverTools(body.autoDiscoverTools != null && body.autoDiscoverTools);
        if (body.auth != null) {
            AuthConfig auth = new AuthConfig();
            auth.setAuthType(body.auth.authType != null ? body.auth.authType : "none");
            auth.setSecretRef(body.auth.secretRef);
            auth.setTokenEndpoint(body.auth.tokenEndpoint);
            auth.setHeaderName(body.auth.headerName);
            c.setAuth(auth);
        }
        c.setDefaultHeaders(body.defaultHeaders);
        return c;
    }

    private static void updateFromRequest(ToolProviderConfig c, ToolProviderConfigCreateUpdateRequest body) {
        if (body.name != null) c.setName(body.name.trim());
        if (body.providerType != null) c.setProviderType(body.providerType);
        if (body.baseUrl != null) c.setBaseUrl(body.baseUrl.trim());
        if (body.timeoutMs > 0) c.setTimeoutMs(body.timeoutMs);
        if (body.maxRetries >= 0) c.setMaxRetries(body.maxRetries);
        if (body.enabled != null) c.setEnabled(body.enabled);
        if (body.mcpTransport != null) c.setMcpTransport(body.mcpTransport.trim());
        if (body.autoDiscoverTools != null) c.setAutoDiscoverTools(body.autoDiscoverTools);
        if (body.auth != null) {
            AuthConfig auth = c.getAuth() != null ? c.getAuth() : new AuthConfig();
            if (body.auth.authType != null) auth.setAuthType(body.auth.authType);
            if (body.auth.secretRef != null) auth.setSecretRef(body.auth.secretRef);
            if (body.auth.tokenEndpoint != null) auth.setTokenEndpoint(body.auth.tokenEndpoint);
            if (body.auth.headerName != null) auth.setHeaderName(body.auth.headerName);
            c.setAuth(auth);
        }
        if (body.defaultHeaders != null) c.setDefaultHeaders(body.defaultHeaders);
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ToolProviderConfigResponse {
        public String id;
        public String refName;
        public String name;
        public ProviderType providerType;
        public String baseUrl;
        public int timeoutMs;
        public int maxRetries;
        public boolean enabled;
        public String mcpTransport;
        public boolean autoDiscoverTools;
        public String lastDiscoverySync;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ToolProviderConfigCreateUpdateRequest {
        public String refName;
        public String name;
        public ProviderType providerType;
        public String baseUrl;
        public int timeoutMs = 30_000;
        public int maxRetries = 2;
        public Boolean enabled;
        public String mcpTransport;
        public Boolean autoDiscoverTools;
        public AuthConfigDto auth;
        public Map<String, String> defaultHeaders;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class AuthConfigDto {
        public String authType;
        public String secretRef;
        public String tokenEndpoint;
        public String headerName;
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public static class ImportToolsRequest {
        public Boolean persist;
    }
}
