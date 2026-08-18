package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.rest.core.BaseResource;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
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

@Path("/user/usergroup")
public class UserGroupResource extends BaseResource<UserGroup, UserGroupRepo> {
   protected UserGroupResource (UserGroupRepo repo) {
      super(repo);
   }

   @Override
   @Path("refName")
   @GET
   @Operation(summary = "Get The entity by refName", description = "Will get the entity or return a 404 if not found")
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses({
         @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserGroup.class))),
         @APIResponse(responseCode = "404", description = "Entity not found")
   })
   public Response byRefName(@Context HttpHeaders headers,
         @Parameter(description = "Reference name of the entity", required = true) @QueryParam("refName") String refName) {
      return super.byRefName(headers, refName);
   }
}
