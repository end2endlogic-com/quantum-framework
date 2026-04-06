package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.TenantOnboardingRunStartRequest;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.onboarding.TenantOnboardingFlowService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Optional;

@Path("/onboarding/run")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@Tag(name = "onboarding", description = "Operations related to tenant onboarding execution")
public class TenantOnboardingRunResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    TenantOnboardingFlowService onboardingFlowService;

    @POST
    @Path("start")
    @Operation(summary = "Start a tenant onboarding run")
    @SecurityRequirement(name = "bearerAuth")
    public Response start(TenantOnboardingRunStartRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(onboardingFlowService.startWorkflow(realm, request)).build();
    }

    @GET
    @Path("{runRef}")
    @Operation(summary = "Get a tenant onboarding run")
    @SecurityRequirement(name = "bearerAuth")
    public Response get(@PathParam("runRef") String runRef) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return onboardingFlowService.getWorkflowRun(realm, runRef)
            .map(Response::ok)
            .orElseThrow(() -> new NotFoundException("Tenant onboarding run not found: " + runRef))
            .build();
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
