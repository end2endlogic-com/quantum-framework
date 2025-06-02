package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.Role;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Path("/user/userProfile")
@RolesAllowed({"user", "admin"})
@Tag(name = "users", description = "Operations related to managing users")
public class UserProfileResource extends BaseResource<UserProfile, UserProfileRepo> {

   UserProfileResource (UserProfileRepo repo ) {
      super(repo);
   }



   @Path("byRefName")
   @GET
   @RolesAllowed({ "user", "admin" })
   @Produces(MediaType.APPLICATION_JSON)
   @PermissionCheck(
      area = "Security",
      functionalDomain="userProfile",
      action="view"
   )
   public Response byRefName (@QueryParam("refName") String refName) {
      return super.byRefName(refName);
   }



   @Path("updateStatus")
   @PUT
   @RolesAllowed({ "user", "admin" })
   @Produces(MediaType.APPLICATION_JSON)
   @PermissionCheck(
      area = "Security",
      functionalDomain="userProfile",
        action="update"
   )
   public Response updateStatus(@QueryParam("userId") String userId, @QueryParam("status") String status) {
      Optional<UserProfile> up = repo.updateStatus(userId, UserProfile.Status.fromValue(status));
      if (up.isPresent()) {
         return Response.ok(up.get()).build();
      } else {
         return Response.notModified().entity(RestError.builder()
              .status(Response.Status.NOT_MODIFIED.getStatusCode())
                 .debugMessage("The user profile was not modified, most likely because the userId could not be resolved to a user profile ")
                 .statusMessage("The update failed, user profile was not modified")
                 .reasonCode(Response.Status.NOT_MODIFIED.getStatusCode())
                 .build()).build();
      }
   }


   /**
    * This is an example taking a dynamic object which is just represented as Json from the front end and then
    * mapping this to class;  Of course the other way is to just take the actual class as the argument but I wanted to
    * show how dynamic objects can be handled as an example here.
    * @param uiUserProfile - pure json object not type checked.
    * @return Response which should be saved object with the id included
    */
   @Path("create")
   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response save(JsonNode uiUserProfile) {

      UserProfile up = new UserProfile();
      up.setUserId(uiUserProfile.get("userId").asText());
      up.setFname(uiUserProfile.get("fname").asText());
      up.setLname(uiUserProfile.get("lname").asText());
      up.setUsername(uiUserProfile.get("userId").asText());
      up.setDisplayName(uiUserProfile.get("displayName").asText());
      up.setEmail(uiUserProfile.get("email").asText());
      up.setPhoneNumber(uiUserProfile.get("phoneNumber").asText());
      up.setRefName(up.getUserId());
      up.setDefaultCurrency(uiUserProfile.get("defaultCurrency").asText());
      up.setDefaultLanguage(uiUserProfile.get("defaultLanguage").asText());
      up.setDefaultUnits(uiUserProfile.get("defaultUnits").asText());
      up.setDefaultTimezone(uiUserProfile.get("defaultTimezone").asText());

      // Default the data domain to the logged in person's data domain:
      SecurityContext.getPrincipalContext().ifPresent(principalContext -> up.setDataDomain(principalContext.getDataDomain()));

      String password;
      if (uiUserProfile.get("password") != null)
           password = uiUserProfile.get("password").asText();
      else
         password = null;

      Response response = null;
      if (password!= null &&!password.isEmpty()) {

         // This should really either be part of the credentials object
         // or passed in separately.  Its here as a place holder for now.
         Set<Role> roles = new HashSet<>();
         roles.add(Role.user);
         int i = 0;
         String[] rolesArray = new String[roles.size()];

         for (Role r : roles) {
            rolesArray[i++] = r.name();
         }

         UserProfile model = repo.createUser(
                 up,
                 rolesArray,
                 password);

         response =  Response.ok().entity(model).status(Response.Status.CREATED).build();
      } else {
         response =  Response.status(Response.Status.BAD_REQUEST).entity(RestError.builder()
                 .reasonMessage("Missing password")
                 .debugMessage("you must provide the initial plain text password to create the user with")
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage(Response.Status.BAD_REQUEST.getReasonPhrase())
                 .build()).build();
      }
      return response;


   }
}
