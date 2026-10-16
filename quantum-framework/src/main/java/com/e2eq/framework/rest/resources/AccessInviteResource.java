package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.AccessInviteAcceptRequest;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.requests.AccessInviteRequest;
import com.e2eq.framework.rest.responses.AccessInviteAcceptResponse;
import com.e2eq.framework.rest.responses.AccessInviteResponse;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
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
    @Operation(summary = "List access invites",
        description = "Administrators see every invite in the realm; other callers see only the invites they created. Invite tokens are never returned.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invites visible to the caller",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
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
    @Operation(summary = "Get access invite", description = "Only the inviter or an administrator of this realm may read an invite. The token is never returned.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "INVITE_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    public Response get(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.getInvite(realm, refName)).build();
    }

    @POST
    @Operation(summary = "Create access invite",
        description = "Binds the invite to the given email or targetUserId; acceptance can only ever apply to that identity. The inviter can grant only roles it holds in this realm, never system. The raw token is returned here only when no invite delivery channel is configured.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Invite created; inviteToken is present only when no delivery channel is configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_ROLE_NOT_GRANTABLE or INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
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
    @Operation(summary = "Revoke access invite", description = "Only the inviter or a realm administrator may revoke, and only while the invite is pending.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite revoked",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "INVITE_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_NOT_PENDING",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    public Response revoke(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.revokeInvite(realm, refName)).build();
    }

    @POST
    @Path("{refName}/resend")
    @Operation(summary = "Resend access invite",
        description = "Only the inviter or a realm administrator may resend a pending invite. Rotates the token (the previous token stops working), renews the expiry, and notifies the invitee. The new token is returned only when no delivery channel is configured.")
    @SecurityRequirement(name = "bearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite re-issued; inviteToken is present only when no delivery channel is configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "INVITE_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_NOT_PENDING",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response resend(@PathParam("refName") String refName, @QueryParam("expiresInDays") Integer expiresInDays) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.resendInvite(realm, refName, expiresInDays)).build();
    }

    @POST
    @Path("accept")
    @PermitAll
    @Operation(summary = "Accept access invite",
        description = "The account identity comes only from the invite (its targetUserId, else its email). "
            + "A body userId or email that differs from the invite is refused (403 INVITE_IDENTITY_MISMATCH). "
            + "If the invited identity already has an account, the caller must be signed in as it (401 INVITE_SIGN_IN_REQUIRED otherwise). "
            + "If not, an account is created for the invited identity and a password is required. Tokens are single-use.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite accepted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteAcceptResponse.class))),
        @APIResponse(responseCode = "400", description = "INVALID_REQUEST or INVITE_TOKEN_INVALID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "401", description = "INVITE_SIGN_IN_REQUIRED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "INVITE_IDENTITY_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_NOT_PENDING (already accepted or revoked)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "410", description = "INVITE_EXPIRED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
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
