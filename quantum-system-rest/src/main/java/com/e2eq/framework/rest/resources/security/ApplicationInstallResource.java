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

/** Installs of applications for tenants of the caller's realm ({@link ApplicationInstall}). */
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
    @Operation(summary = "List installs", description = "Installs in the caller's realm for tenants the caller administers.")
    @APIResponse(responseCode = "200", description = "Installs",
        content = @Content(mediaType = "application/json", schema = @Schema(type = SchemaType.ARRAY, implementation = ApplicationInstall.class)))
    public Response list() {
        return Response.ok(accessService.listInstalls(realm())).build();
    }

    @POST
    @FunctionalAction("INSTALL")
    @Operation(summary = "Install an application for a tenant",
        description = "The platform or a realm administrator of the tenant. With an id the install is written under it, so a repeat "
            + "changes nothing; without one an existing active install for the tenant is returned.")
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
            + "but stop granting the tenant.")
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
