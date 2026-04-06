package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.requests.TenantProvisioningWorkflowRequest;
import com.e2eq.framework.service.provisioning.TenantProvisioningWorkflowService;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/admin/tenants/workflow")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "system"})
@Tag(name = "security", description = "Operations related to tenant provisioning workflow configuration")
public class TenantProvisioningWorkflowResource {

    @Inject
    TenantProvisioningWorkflowService workflowService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @GET
    @Path("current")
    @Operation(summary = "Get the current tenant provisioning workflow")
    @SecurityRequirement(name = "bearerAuth")
    public Response getCurrent() {
        return Response.ok(workflowService.getCurrentWorkflow(envConfigUtils.getSystemRealm())).build();
    }

    @POST
    @Path("current")
    @Operation(summary = "Save the current tenant provisioning workflow")
    @SecurityRequirement(name = "bearerAuth")
    public Response saveCurrent(TenantProvisioningWorkflowRequest request) {
        return Response.ok(workflowService.saveCurrentWorkflow(envConfigUtils.getSystemRealm(), request)).build();
    }
}
