package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.rest.core.BaseResource;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/security/application")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@FunctionalMapping(area = "security", domain = "application")
public class ApplicationResource extends BaseResource<Application, ApplicationRepo> {
   @Inject
   SecurityUtils securityUtils;

   protected ApplicationResource (ApplicationRepo repo) {
      super(repo);
   }

   @Override
   @Path("refName")
   @GET
   @Operation(summary = "Get The entity by refName", description = "Will get the entity or return a 404 if not found")
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses({
         @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Application.class))),
         @APIResponse(responseCode = "404", description = "Entity not found")
   })
   public Response byRefName(@Context HttpHeaders headers,
         @Parameter(description = "Reference name of the entity", required = true) @QueryParam("refName") String refName) {
      return super.byRefName(headers, refName);
   }

   /**
    * The application collection is a global system-plane registry. A caller's omitted X-Realm
    * means "use my default realm", not "filter global registry rows using my tenant domain".
    * Keep the authenticated identity, roles, audience and resource policy context, but resolve
    * row visibility against the authoritative system data domain for the headerless default.
    */
   @Override
   public com.e2eq.framework.rest.models.Collection<Application> getList(
         HttpHeaders headers, int skip, int limit, String filter, String sort,
         String projection, Boolean uiActions) {
      if (headers.getHeaderString("X-Realm") != null) {
         return super.getList(headers, skip, limit, filter, sort, projection, uiActions);
      }
      PrincipalContext current = SecurityContext.getPrincipalContext().orElse(null);
      var resource = SecurityContext.getResourceContext().orElse(null);
      if (current == null || resource == null) {
         return super.getList(headers, skip, limit, filter, sort, projection, uiActions);
      }
      PrincipalContext registryPrincipal = new PrincipalContext.Builder()
         .withDefaultRealm(current.getDefaultRealm())
         .withApplicationId(current.getApplicationId())
         .withDomainContext(current.getDomainContext())
         .withDataDomain(securityUtils.getSystemDataDomain())
         .withUserId(current.getUserId())
         .withSubjectId(current.getSubjectId())
         .withRoles(current.getRoles())
         .withScope(current.getScope())
         .withDataDomainPolicy(current.getDataDomainPolicy())
         .build();
      return SecurityCallScope.runWithContexts(registryPrincipal, resource,
         () -> super.getList(headers, skip, limit, filter, sort, projection, uiActions));
   }
}
