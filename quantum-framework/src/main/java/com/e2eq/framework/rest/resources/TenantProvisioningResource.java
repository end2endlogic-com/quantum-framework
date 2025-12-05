package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.TenantProvisioningService;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Minimal REST endpoint to provision a new tenant. Intended for admin use.
 */
@Path("/admin/tenants")
@Tag(name = "security", description = "Operations related to tenant provisioning")
public class TenantProvisioningResource {

    @Inject TenantProvisioningService provisioningService;

    public static class ProvisionTenantRequest {
        @NotBlank public String tenantEmailDomain; // e.g., acme.com
        @NotBlank public String orgRefName;        // e.g., acme.com
        @NotBlank public String accountId;         // e.g., initial account number
        @NotBlank public String adminUserId;       // e.g., admin@acme.com
        @NotBlank public String adminUsername;     // e.g., "Acme Admin"
        @NotBlank public String adminPassword;     // plaintext; hashed in service
        public List<String> archetypes;            // optional list of archetype names
    }

    public static class ProvisionTenantResponse {
        public String realmId;
        public boolean realmCreated;
        public boolean userCreated;
        public java.util.List<String> warnings;
        public ProvisionTenantResponse(TenantProvisioningService.ProvisionResult r) {
            this.realmId = r.realmId;
            this.realmCreated = r.realmCreated;
            this.userCreated = r.userCreated;
            this.warnings = r.warnings;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin"})
    public Response provision(ProvisionTenantRequest req) {
        try {
            List<String> archetypes = req.archetypes == null ? java.util.List.of() : req.archetypes;
            TenantProvisioningService.ProvisionResult r = provisioningService.provisionTenant(
                    req.tenantEmailDomain,
                    req.orgRefName,
                    req.accountId,
                    req.adminUserId,
                    req.adminUsername, // subject ?
                    req.adminPassword,
                    archetypes,
                    true
            );
            int status = (r.realmCreated || r.userCreated) ? Response.Status.CREATED.getStatusCode() : Response.Status.OK.getStatusCode();
            return Response.status(status).entity(new ProvisionTenantResponse(r)).build();
        } catch (IllegalStateException ise) {
            Log.warn("Provisioning rejected: " + ise.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(ise.getMessage()).build();
        } catch (Exception e) {
            Log.error("Provisioning failed", e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
