package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.requests.TenantProvisioningRetryRequest;
import com.e2eq.framework.rest.responses.TenantProvisioningRunResponse;
import com.e2eq.framework.service.TenantProvisioningService;
import com.e2eq.framework.service.provisioning.TenantProvisioningRunFailedException;
import com.e2eq.framework.service.provisioning.TenantProvisioningRunService;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
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

    @Inject TenantProvisioningRunService provisioningRunService;

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
            TenantProvisioningRunResponse run = provisioningRunService.startProvisioning(
                TenantProvisioningService.ProvisionTenantCommand.builder()
                    .tenantDisplayName(req.tenantDisplayName)
                    .tenantEmailDomain(req.tenantEmailDomain)
                    .orgRefName(req.orgRefName)
                    .accountId(req.accountId)
                    .adminUserId(req.adminUserId)
                    .adminSubject(resolveAdminSubject(req))
                    .adminPassword(req.adminPassword)
                    .archetypes(archetypes)
                    .overwriteAll(true)
                    .build()
            );
            int status = (run.isRealmCreated() || run.isUserCreated())
                ? Response.Status.CREATED.getStatusCode()
                : Response.Status.OK.getStatusCode();
            return Response.status(status).entity(run).build();
        } catch (TenantProvisioningRunFailedException failedException) {
            Log.warn("Provisioning failed: " + failedException.getMessage());
            throw buildProvisioningException(failedException);
        } catch (IllegalArgumentException e) {
            Log.warn("Provisioning rejected: " + e.getMessage());
            throw buildProvisioningException(Response.Status.BAD_REQUEST, e.getMessage(), e.getMessage(), null);
        } catch (IllegalStateException e) {
            Log.warn("Provisioning rejected: " + e.getMessage());
            throw buildProvisioningException(Response.Status.CONFLICT, e.getMessage(), e.getMessage(), null);
        } catch (Exception e) {
            Log.error("Provisioning failed", e);
            throw buildProvisioningException(Response.Status.INTERNAL_SERVER_ERROR, "Tenant provisioning failed.", e.getMessage(), null);
        }
    }

    @GET
    @Path("runs/{executionRef}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "system"})
    public Response getRun(@PathParam("executionRef") String executionRef) {
        return Response.ok(provisioningRunService.getRun(executionRef)).build();
    }

    @POST
    @Path("runs/{executionRef}/retry")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "system"})
    public Response retry(@PathParam("executionRef") String executionRef, TenantProvisioningRetryRequest request) {
        try {
            TenantProvisioningRunResponse run = provisioningRunService.retry(
                executionRef,
                request != null ? request.getAdminPassword() : null
            );
            return Response.ok(run).build();
        } catch (TenantProvisioningRunFailedException failedException) {
            Log.warn("Provisioning retry failed: " + failedException.getMessage());
            throw buildProvisioningException(failedException);
        } catch (IllegalArgumentException e) {
            Log.warn("Provisioning retry rejected: " + e.getMessage());
            throw buildProvisioningException(Response.Status.BAD_REQUEST, e.getMessage(), e.getMessage(), null);
        } catch (Exception e) {
            Log.error("Provisioning retry failed", e);
            throw buildProvisioningException(Response.Status.INTERNAL_SERVER_ERROR, "Tenant provisioning retry failed.", e.getMessage(), null);
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

    private WebApplicationException buildProvisioningException(TenantProvisioningRunFailedException exception) {
        TenantProvisioningRunResponse run = provisioningRunService.getRun(exception.getRun().getExecutionRef());
        return buildProvisioningException(
            exception.getResponseStatus(),
            run.getFailureReason(),
            run.getFailureDetail(),
            run.getExecutionRef()
        );
    }

    private WebApplicationException buildProvisioningException(
        Response.Status status,
        String statusMessage,
        String detail,
        String executionRef
    ) {
        RestError error = RestError.builder()
            .status(status.getStatusCode())
            .reasonCode(status.getStatusCode())
            .statusMessage(statusMessage)
            .reasonMessage(detail)
            .debugMessage(executionRef == null ? null : "tenantProvisioningExecutionRef=" + executionRef)
            .build();
        return new WebApplicationException(Response.status(status).entity(error).build());
    }
}
