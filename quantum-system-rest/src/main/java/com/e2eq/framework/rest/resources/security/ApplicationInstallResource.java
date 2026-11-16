package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.service.access.ApplicationAccessService;
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
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Installs of applications for tenants of a realm ({@link ApplicationInstall}): the caller's, or any for the platform. */
@FunctionalMapping(area = "SECURITY", domain = "APPLICATION_INSTALL")
@Path("/api/security/applicationInstall")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "application-access", description = "Application bindings and installs")
public class ApplicationInstallResource {

    @Inject
    RuleContext ruleContext;

    @Inject
    ApplicationAccessService accessService;

    @GET
    @Path("list")
    @FunctionalAction("LIST")
    @Operation(summary = "List installs",
        description = "Installs of one realm, whatever their status, in creation order, narrowed by the optional filters (a blank "
            + "filter is absent). Without realmRefName the realm is the caller's. Only the platform may name another realm; "
            + "anyone else naming one is refused REALM_MISMATCH. The platform sees every install of the realm; anyone else only "
            + "those of tenants they administer. An applicationId that names no registered application is refused "
            + "APPLICATION_UNKNOWN; no match is an empty list.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Installs",
            content = @Content(mediaType = "application/json", schema = @Schema(type = SchemaType.ARRAY, implementation = ApplicationInstall.class))),
        @APIResponse(responseCode = "400", description = "APPLICATION_UNKNOWN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "REALM_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response list(
        @Parameter(description = "The realm whose installs are listed; another than the caller's only for the platform")
        @QueryParam("realmRefName") String realmRefName,
        @Parameter(description = "Only installs of this application (an Application refName)")
        @QueryParam("applicationId") String applicationId,
        @Parameter(description = "Only installs for this tenant")
        @QueryParam("tenantId") String tenantId) {
        return Response.ok(accessService.listInstalls(realm(), realmRefName, applicationId, tenantId)).build();
    }

    @POST
    @FunctionalAction("INSTALL")
    @Operation(summary = "Install an application for a tenant",
        description = "The platform or a realm administrator of the tenant. With an id the install is written under it, so a repeat "
            + "changes nothing; without one an existing active install for the tenant is returned. The application's own service "
            + "identity may install that application in a realm the application declares (tenancy placement REALMS), always without "
            + "an id (an install with one is refused INSTALL_NOT_AUTHORIZED).")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The install",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApplicationInstall.class))),
        @APIResponse(responseCode = "400", description = "INSTALL_INVALID or APPLICATION_UNKNOWN",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "403", description = "INSTALL_NOT_AUTHORIZED or REALM_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response install(ApplicationInstall install) {
        return Response.ok(accessService.install(realm(), install)).build();
    }

    @POST
    @Path("id/{id}/uninstall")
    @FunctionalAction("UNINSTALL")
    @Operation(summary = "Uninstall",
        description = "Marks the install uninstalled and removes the application's group bindings for the tenant. User bindings stay "
            + "but stop granting the tenant. The platform or a realm administrator of the tenant, or the application's own service "
            + "identity for an install of that application in a realm it declares.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The uninstalled install",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApplicationInstall.class))),
        @APIResponse(responseCode = "403", description = "INSTALL_NOT_AUTHORIZED or REALM_MISMATCH",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class))),
        @APIResponse(responseCode = "404", description = "NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response uninstall(@PathParam("id") String id) {
        return Response.ok(accessService.uninstall(realm(), UserAppRoleResource.objectId(id))).build();
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
