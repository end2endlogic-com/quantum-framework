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
    }

    public static class ProvisionTenantResponse {
        public String realmId;
        public ProvisionTenantResponse(String realmId) { this.realmId = realmId; }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin"})
    public Response provision(ProvisionTenantRequest req) {
        try {
            String realmId = provisioningService.provisionTenant(
                    req.tenantEmailDomain,
                    req.orgRefName,
                    req.accountId,
                    req.adminUserId,
                    req.adminUsername,
                    req.adminPassword
            );
            return Response.status(Response.Status.CREATED).entity(new ProvisionTenantResponse(realmId)).build();
        } catch (IllegalStateException ise) {
            Log.warn("Provisioning rejected: " + ise.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(ise.getMessage()).build();
        } catch (Exception e) {
            Log.error("Provisioning failed", e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
