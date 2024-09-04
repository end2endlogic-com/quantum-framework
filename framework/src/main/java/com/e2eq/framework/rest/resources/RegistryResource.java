package com.e2eq.framework.rest.resources;


import com.e2eq.framework.exceptions.E2eqValidationException;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;


import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Optional;

@Path("/onboarding/registrationRequest")
@RolesAllowed({ "user", "admin" })
public class RegistryResource extends BaseResource<ApplicationRegistration, BaseMorphiaRepo<ApplicationRegistration>> {

   public RegistryResource (ApplicationRegistrationRequestRepo repo ) {
      super(repo);
   }

   @POST
   @Path("create")
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @PermissionCheck(
           area = "onboarding",
           functionalDomain="registrationRequest",
           action="create"
   )
   public Response create(ApplicationRegistration request) {
      return super.save(request);
   }

   @Path("approve")
   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response approve(ApplicationRegistration request) throws E2eqValidationException {
      ApplicationRegistrationRequestRepo regRepo = (ApplicationRegistrationRequestRepo) repo;

      Optional<ApplicationRegistration> rc = regRepo.approveRequest(request.getId().toString());
      if (rc.isPresent()) {
         return Response.ok(rc.get()).build();
      } else {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
   }
}
