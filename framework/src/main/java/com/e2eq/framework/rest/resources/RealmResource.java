package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Optional;


@Path("/security/realm")
@RolesAllowed({ "admin" })
@Tag(name = "security", description = "Operations related to security")
public class RealmResource extends BaseResource<Realm, BaseMorphiaRepo<Realm>> {
   protected RealmResource (RealmRepo repo) {
      super(repo);
   }

   @Path("byTenantId")
   @GET
   @RolesAllowed({"user", "admin"})
   @PermissionCheck(
      area = "Security",
      functionalDomain="Realm",
      action="view"
   )
   @Produces({ "application/json"})
   @Consumes({"application/json"})
   public Response byTenantId(@QueryParam("tenantId") String tenantId) {
        RealmRepo rrepo = (RealmRepo) this.repo;
        Optional<Realm> orealm = rrepo.findByTenantId(tenantId, false);
        if (orealm.isPresent()) {
           return Response.ok(orealm.get()).build();
        } else {
           RestError error = RestError.builder()
                   .status(Response.Status.NOT_FOUND.getStatusCode())
                   .statusMessage("Could not find realm for tenantId:" + tenantId).build();
           return Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }
   }

}
