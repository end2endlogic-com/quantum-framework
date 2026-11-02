package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.responses.AccessMemberResponse;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.access.AccessInviteService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Optional;

@FunctionalMapping(area = "SECURITY", domain = "USER_REALM_ROLE")
@Path("/access/members")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@Tag(name = "access-invites", description = "Operations related to shared access invites")
public class AccessMemberResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    AccessInviteService inviteService;

    @GET
    @Operation(summary = "List the members of a tenant",
        description = "Lists the members of one tenant of this realm, whatever their status: in a pooled realm the assignments that name the "
            + "tenant in authorizedTenantIds (not those that reach it only through authorizedTenantRegEx), in a dedicated realm every assignment. "
            + "tenantId defaults to the tenant of the request. Allowed for the system principal, a realm administrator "
            + "(in a pooled realm only for tenants its realm assignment authorizes), and a membership administrator: a caller holding a role "
            + "listed in quantum.access-invites.membership-admin-roles in its active realm assignment, for the tenant of the request only; "
            + "in a pooled realm the assignment must authorize that one tenant and no other. Credentials and tokens are never returned.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Members of the tenant",
            content = @Content(mediaType = "application/json", schema = @Schema(type = SchemaType.ARRAY, implementation = AccessMemberResponse.class))),
        @APIResponse(responseCode = "400", description = "INVALID_REQUEST: no tenant given and none in the token",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "INVITE_NOT_AUTHORIZED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    @SecurityRequirement(name = "bearerAuth")
    public Response list(@QueryParam("tenantId") String tenantId) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(inviteService.listTenantMembers(realm, tenantId)).build();
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
