package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.TenantProvisioningService;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PathParam;
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
        public String tenantDisplayName;          // human-readable tenant label shown in catalogs
        @NotBlank public String tenantEmailDomain; // e.g., acme.com
        @NotBlank public String orgRefName;        // e.g., acme.com
        @NotBlank public String accountId;         // e.g., initial account number
        @NotBlank public String adminUserId;       // e.g., admin@acme.com
        public String adminUsername;               // legacy display field; not used for subject identity
        public String adminSubject;                // optional stable subject override; defaults to adminUserId
        @NotBlank public String adminPassword;     // plaintext; hashed in service
        public List<String> archetypes;            // legacy alias for seedArchetypes
        public List<String> seedArchetypes;        // optional list of seed-framework archetype names
    }

    public static class ProvisionTenantResponse {
        public String realmId;
        public boolean realmCreated;
        public boolean userCreated;
        public java.util.List<String> appliedSeedArchetypes;
        public java.util.List<String> warnings;
        public ProvisionTenantResponse(TenantProvisioningService.ProvisionResult r, java.util.List<String> appliedSeedArchetypes) {
            this.realmId = r.realmId;
            this.realmCreated = r.realmCreated;
            this.userCreated = r.userCreated;
            this.appliedSeedArchetypes = appliedSeedArchetypes;
            this.warnings = r.warnings;
        }
    }

    public static class DeleteTenantResponse {
        public String realmId;
        public boolean realmCatalogDeleted;
        public boolean databaseDropped;
        public int deletedCredentialCount;
        public java.util.List<String> warnings;

        public DeleteTenantResponse(TenantProvisioningService.DeleteResult r) {
            this.realmId = r.realmId;
            this.realmCatalogDeleted = r.realmCatalogDeleted;
            this.databaseDropped = r.databaseDropped;
            this.deletedCredentialCount = r.deletedCredentialCount;
            this.warnings = r.warnings;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "system"})
    public Response provision(ProvisionTenantRequest req) {
        try {
            List<String> archetypes = firstNonEmpty(req.seedArchetypes, req.archetypes);
            String adminSubject = resolveAdminSubject(req);
            TenantProvisioningService.ProvisionResult r = provisioningService.provisionTenant(
                    req.tenantDisplayName,
                    req.tenantEmailDomain,
                    req.orgRefName,
                    req.accountId,
                    req.adminUserId,
                    adminSubject,
                    req.adminPassword,
                    archetypes,
                    true
            );
            int status = (r.realmCreated || r.userCreated) ? Response.Status.CREATED.getStatusCode() : Response.Status.OK.getStatusCode();
            return Response.status(status).entity(new ProvisionTenantResponse(r, archetypes)).build();
        } catch (IllegalStateException ise) {
            Log.warn("Provisioning rejected: " + ise.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(ise.getMessage()).build();
        } catch (Exception e) {
            Log.error("Provisioning failed", e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/{realmId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "system"})
    public Response deleteTenant(@PathParam("realmId") String realmId) {
        try {
            TenantProvisioningService.DeleteResult r = provisioningService.deleteTenant(realmId);
            return Response.ok(new DeleteTenantResponse(r)).build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.warn("Tenant deletion rejected: " + e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (Exception e) {
            Log.error("Tenant deletion failed", e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    private static List<String> firstNonEmpty(List<String> preferred, List<String> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred.stream().filter(value -> value != null && !value.isBlank()).toList();
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.stream().filter(value -> value != null && !value.isBlank()).toList();
        }
        return java.util.List.of();
    }

    private static String resolveAdminSubject(ProvisionTenantRequest req) {
        if (req.adminSubject != null && !req.adminSubject.isBlank()) {
            return req.adminSubject.trim();
        }
        return req.adminUserId;
    }
}
