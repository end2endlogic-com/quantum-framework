package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.access.AccessInviteService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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

@Path("/access/invites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@Tag(name = "access-invites", description = "Operations related to shared access invites")
public class AccessInviteResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    AccessInviteService inviteService;

    @GET
    @Operation(summary = "List access invites")
    @SecurityRequirement(name = "bearerAuth")
    public Response list() {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.listInvites(realm)).build();
    }

    @GET
    @Path("{refName}")
    @Operation(summary = "Get access invite")
    @SecurityRequirement(name = "bearerAuth")
    public Response get(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.getInvite(realm, refName)).build();
    }

    @POST
    @Operation(summary = "Create access invite")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(AccessInviteRequest request) {
        Optional<PrincipalContext> principal = SecurityContext.getPrincipalContext();
        String realm = getRealmId();
        if (realm == null || principal.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.status(Response.Status.CREATED)
            .entity(inviteService.createInvite(realm, principal.get().getUserId(), request))
            .build();
    }

    @POST
    @Path("{refName}/revoke")
    @Operation(summary = "Revoke access invite")
    @SecurityRequirement(name = "bearerAuth")
    public Response revoke(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.revokeInvite(realm, refName)).build();
    }

    @POST
    @Path("accept")
    @PermitAll
    @Operation(summary = "Accept access invite")
    public Response accept(AccessInviteAcceptRequest request) {
        return Response.ok(inviteService.acceptInvite(request)).build();
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
