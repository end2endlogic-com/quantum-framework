package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.access.ApplicationAccessService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Application bindings ({@link UserAppRole}) of the caller's realm. Writes are checked against
 * the application's declared roles, each role's {@code grantableBy}, the caller's administrator
 * authority over every tenant of the binding, and the application's installs. There is no generic
 * CRUD surface: every write goes through these checks.
 */
@FunctionalMapping(area = "SECURITY", domain = "USER_APP_ROLE")
@Path("/api/security/userAppRole")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "application-access", description = "Application bindings and installs")
public class UserAppRoleResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    ApplicationAccessService accessService;

    @GET
    @Path("list")
    @FunctionalAction("LIST")
    @Operation(summary = "List an application's bindings for a tenant",
        description = "Bindings in the caller's realm for applicationId that name tenantId (default: the tenant of the request). "
            + "Allowed for the platform and administrators of that tenant.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Bindings",
            content = @Content(mediaType = "application/json", schema = @Schema(type = SchemaType.ARRAY, implementation = UserAppRole.class))),
        @APIResponse(responseCode = "400", description = "APPLICATION_UNKNOWN or BINDING_INVALID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "ROLE_NOT_GRANTABLE: the caller does not administer the tenant",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response list(@QueryParam("applicationId") String applicationId, @QueryParam("tenantId") String tenantId) {
        return Response.ok(accessService.listBindings(realm(), applicationId, tenantId)).build();
    }

    @GET
    @Path("user/{userId}")
    @FunctionalAction("LIST")
    @Operation(summary = "One person's binding of an application",
        description = "The binding userId holds for applicationId in the caller's realm, with every tenant it covers. "
            + "Read it before writing that person's roles: there is one binding per (user, application, realm), so a PUT "
            + "replaces what it holds for its other tenants. Allowed for the platform, and for an administrator of the "
            + "request's tenant who also administers every tenant of the binding.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The binding",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserAppRole.class))),
        @APIResponse(responseCode = "400", description = "APPLICATION_UNKNOWN, BINDING_INVALID or BINDING_SUBJECT_INVALID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "ROLE_NOT_GRANTABLE: the caller does not administer the request's tenant, or not every tenant of the binding",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "NOT_FOUND: the person holds no binding of the application in the realm",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response userBinding(@PathParam("userId") String userId, @QueryParam("applicationId") String applicationId) {
        return Response.ok(accessService.findUserBinding(realm(), applicationId, userId)
            .orElseThrow(() -> new ApplicationAccessException(ApplicationAccessException.Code.NOT_FOUND,
                "no binding of '" + applicationId + "' for " + userId))).build();
    }

    @PUT
    @FunctionalAction("SAVE")
    @Operation(summary = "Create or replace a binding",
        description = "One binding per (user or group, application, realm). The realm is the caller's (only the platform may name another). "
            + "Roles must be declared by the application and grantable by the caller (platform; realm-admin: roles grantable by realm-admin "
            + "or tenant-admin; tenant-admin: roles grantable by tenant-admin; system never). The caller must administer every tenant. "
            + "Several tenants need a defaultTenantId among them, and the application must be installed for each tenant.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The stored binding",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserAppRole.class))),
        @APIResponse(responseCode = "400", description = "BINDING_SUBJECT_INVALID, BINDING_INVALID, APPLICATION_UNKNOWN, ROLE_NOT_DECLARED, TENANTS_REQUIRED or TENANT_DEFAULT_REQUIRED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "ROLE_NOT_GRANTABLE or REALM_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "409", description = "APPLICATION_NOT_INSTALLED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response put(UserAppRole binding) {
        return Response.ok(accessService.putBinding(realm(), binding)).build();
    }

    @DELETE
    @Path("id/{id}")
    @FunctionalAction("DELETE")
    @Operation(summary = "Delete a binding", description = "The same authority as writing it.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "403", description = "ROLE_NOT_GRANTABLE or REALM_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response delete(@PathParam("id") String id) {
        accessService.deleteBinding(realm(), objectId(id));
        return Response.noContent().build();
    }

    static ObjectId objectId(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new ApplicationAccessException(ApplicationAccessException.Code.NOT_FOUND, "no record " + id);
        }
        return new ObjectId(id);
    }

    String realm() {
        PrincipalContext principal = SecurityContext.getPrincipalContext().orElse(null);
        ResourceContext resource = SecurityContext.getResourceContext().orElse(null);
        if (principal == null || resource == null) {
            throw new ApplicationAccessException(ApplicationAccessException.Code.REALM_MISMATCH, "the request has no resolved realm");
        }
        return ruleContext.getRealmId(principal, resource);
    }
}
