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
        description = "Realm administrators (the system principal, or a caller that can access this realm and holds admin here as a flat or realm-scoped role) see every invite in the realm; other callers see only the invites they created. Invite tokens are never returned.")
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
        description = "An invite is for exactly one realm: the realm of this request (realm / X-Realm). To grant several realms, send one invite per realm; "
            + "a body realm naming another realm, a realms list with more than one realm, or a wildcard is refused (400 INVITE_SINGLE_REALM_REQUIRED). "
            + "The inviter must be able to access that realm by its own credential (authorizedRealms, a full-match realmRegEx where * means every realm, or its home realm); "
            + "the system realm is reserved for the framework system user (403 INVITE_REALM_NOT_ACCESSIBLE). "
            + "The inviter can grant only roles it holds in that same realm (flat plus realm-scoped roles), never system, and only applications it may grant there. "
            + "Granted roles and applications apply only in that realm: on acceptance they are written to the invitee's UserRealmRole for the realm, never to the credential's flat roles. "
            + "Binds the invite to the given email or targetUserId; acceptance can only ever apply to that identity. The raw token is returned here only when no invite delivery channel is configured. "
            + "The invite records the realm's acceptance policy (dedicated-realm by default; a pooled realm must configure one, else 409 INVITE_POLICY_UNAVAILABLE; a body acceptancePolicy may only repeat it) "
            + "and a scope snapshot: the inviter's data partition resolved from its verified principal (DataDomainResolver), never from the body. A body scope may only echo it (403 INVITE_SCOPE_NOT_AUTHORIZED otherwise). "
            + "Under pooled-realm-tenant the inviter must be entitled to the partition's tenant (its UserRealmRole.authorizedTenantIds/authorizedTenantRegEx for the realm, or realm administrator), else 403 INVITE_SCOPE_NOT_AUTHORIZED; "
            + "a partition that cannot be resolved or carried to the invitee is refused (403 INVITE_SCOPE_UNRESOLVABLE).")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Invite created; inviteToken is present only when no delivery channel is configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "400", description = "INVITE_SINGLE_REALM_REQUIRED or INVALID_REQUEST (for example an unknown application)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "INVITE_REALM_NOT_ACCESSIBLE, INVITE_ROLE_NOT_GRANTABLE, INVITE_APPLICATION_NOT_GRANTABLE, INVITE_NOT_AUTHORIZED, INVITE_SCOPE_NOT_AUTHORIZED, or INVITE_SCOPE_UNRESOLVABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_POLICY_UNAVAILABLE (no usable acceptance policy for the realm, or the body named another policy), or a pending invite already exists for the identity",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "503", description = "The application registry could not be consulted; the invite was not created")
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
        description = "Only the inviter or a realm administrator may resend a pending invite. Issues a new token, renews the expiry, and notifies the invitee; the previous token stops working only after the notification succeeds. If delivery fails nothing is re-issued and the previous token still works. The new token is returned only when no delivery channel is configured.")
    @SecurityRequirement(name = "bearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite re-issued; inviteToken is present only when no delivery channel is configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteResponse.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "INVITE_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_NOT_PENDING (including an invite that changed while it was being resent)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "502", description = "INVITE_NOTIFICATION_FAILED: the new token could not be delivered; nothing was re-issued and the previous token still works",
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
            + "If not, an account is created for the invited identity (flat role user only) and a password is required. "
            + "The invite's roles and applications are written to the user's UserRealmRole for the invite's realm only, merged by union with any existing assignment; never to the credential's flat roles. "
            + "The inviter's realm access and grantable roles are re-checked, including any flat roles an existing account would bring into the realm; if the inviter does not qualify the invite stays pending (403). "
            + "The realm must be registered (otherwise 400 INVITE_TOKEN_INVALID). If a later step fails, the grant is rolled back and the invite stays pending. Tokens are single-use. "
            + "The realm's acceptance policy (it must still be the policy recorded on the invite, else 409 INVITE_POLICY_UNAVAILABLE) re-checks the inviter's entitlement to the recorded scope (403 INVITE_SCOPE_NOT_AUTHORIZED, invite stays pending) and decides the grant. "
            + "Under pooled-realm-tenant the invitee is placed in the inviter's partition (owner = invitee), the tenant is unioned into UserRealmRole.authorizedTenantIds, and roles stay realm-scoped; the response tenantId names it. "
            + "The policy runs before any AccessInviteProvisioner.onInviteAccepted.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invite accepted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessInviteAcceptResponse.class))),
        @APIResponse(responseCode = "400", description = "INVALID_REQUEST or INVITE_TOKEN_INVALID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "401", description = "INVITE_SIGN_IN_REQUIRED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "INVITE_IDENTITY_MISMATCH; or INVITE_REALM_NOT_ACCESSIBLE, INVITE_ROLE_NOT_GRANTABLE, INVITE_APPLICATION_NOT_GRANTABLE, INVITE_SCOPE_NOT_AUTHORIZED, INVITE_SCOPE_UNRESOLVABLE when the inviter or its scope no longer qualifies",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "INVITE_NOT_PENDING (already accepted or revoked), INVITE_ACCOUNT_REALM_CONFLICT (the account's realmRegEx does not include this realm; an administrator must grant it), or INVITE_POLICY_UNAVAILABLE (no usable acceptance policy for the realm)",
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
