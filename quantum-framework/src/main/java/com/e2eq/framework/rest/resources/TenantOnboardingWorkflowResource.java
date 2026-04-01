package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.TenantOnboardingWorkflowRequest;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.onboarding.TenantOnboardingWorkflowService;
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

import java.util.Optional;

@Path("/onboarding/workflow")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@Tag(name = "onboarding", description = "Operations related to tenant onboarding workflow configuration")
public class TenantOnboardingWorkflowResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    TenantOnboardingWorkflowService onboardingWorkflowService;

    @GET
    @Path("current")
    @Operation(summary = "Get the current tenant onboarding workflow")
    @SecurityRequirement(name = "bearerAuth")
    public Response getCurrent() {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(onboardingWorkflowService.getCurrentWorkflow(realm)).build();
    }

    @POST
    @Path("current")
    @RolesAllowed({"admin", "system"})
    @Operation(summary = "Save the current tenant onboarding workflow")
    @SecurityRequirement(name = "bearerAuth")
    public Response saveCurrent(TenantOnboardingWorkflowRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(onboardingWorkflowService.saveCurrentWorkflow(realm, request)).build();
    }

    private String getRealmId() {
        Optional<PrincipalContext> pc = SecurityContext.getPrincipalContext();
        Optional<ResourceContext> rc = SecurityContext.getResourceContext();
        if (pc.isEmpty() || rc.isEmpty()) {
            return null;
        }
        return ruleContext.getRealmId(pc.get(), rc.get());
    }
}
