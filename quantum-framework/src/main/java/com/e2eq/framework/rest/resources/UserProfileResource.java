package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.rest.requests.CreateUserRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Optional;

@Path("/user/userProfile")
@RolesAllowed({"user", "admin", "system"})
@Tag(name = "users", description = "Operations related to managing users")
public class UserProfileResource extends BaseResource<UserProfile, UserProfileRepo> {

   @Inject
   protected AuthProviderFactory authProviderFactory;

   @Inject
   protected CredentialRepo credentialRepo;

   protected UserProfileResource (UserProfileRepo repo ) {
      super(repo);
   }



   @Path("byRefName")
   @GET
   @RolesAllowed({ "user", "admin", "system" })
   @Produces(MediaType.APPLICATION_JSON)
   @PermissionCheck(
      area = "Security",
      functionalDomain="userProfile",
      action="view"
   )
   public Response byRefName (@Context HttpHeaders headers, @QueryParam("refName") String refName) {
      return super.byRefName(headers,refName);
   }



   @Path("updateStatus")
   @PUT
   @RolesAllowed({ "user", "admin", "system" })
   @Produces(MediaType.APPLICATION_JSON)
   @PermissionCheck(
      area = "Security",
      functionalDomain="userProfile",
        action="update"
   )
   public Response updateStatus(@Context HttpHeaders headers, @QueryParam("userId") String userId, @QueryParam("status") String status) {

      String realm = headers.getHeaderString("X-Realm");

      Optional<UserProfile> up;
      if (realm==null) up = repo.updateStatus(userId, UserProfile.Status.fromValue(status)); else up = repo.updateStatus(realm, userId, UserProfile.Status.fromValue(status));
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
    * @param createUserRequest - the request to create a new user
    * @return Response which should be saved object with the id included
    */
   @Path("create")
   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response create(CreateUserRequest createUserRequest) {
      // first see if a user already exists with this userId
      Optional<UserProfile> oup = repo.getByUserId(createUserRequest.getUserId());
      UserProfile up;
      if (oup.isPresent()) {
         return Response.status(Response.Status.CONFLICT).entity(RestError.builder()
                                                                    .status(Response.Status.CONFLICT.getStatusCode())
                                                                    .debugMessage("The user profile already exists, remove the user profile and try again")
                                                                    .statusMessage("The create failed, user profile already exists")
                                                                    .reasonCode(Response.Status.CONFLICT.getStatusCode())
                                                                    .build()).build();
      } else {

         // create the credential

         authProviderFactory.getUserManager().createUser(
            createUserRequest.getUserId(),
            createUserRequest.getPassword(),
            createUserRequest.getForceChangePassword(),
            createUserRequest.getRoles(),
            createUserRequest.getDomainContext());

         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(createUserRequest.getUserId());
         if (!ocred.isPresent())
            throw new IllegalStateException(String.format("User created but credential not found for userId: %s", createUserRequest.getUserId()));


         up = UserProfile.builder()
                 .credentialUserIdPasswordRef(ocred.get().createEntityReference())
                 .email(createUserRequest.getEmail())
                 .phoneNumber(createUserRequest.getPhoneNumber())
                 .displayName(createUserRequest.getDisplayName())
                 .fname(createUserRequest.getFirstName())
                 .lname(createUserRequest.getLastName())
                 .activeStatus(ActiveStatus.ACTIVE)
                 .defaultLanguage( createUserRequest.getDefaultLanguage() != null? createUserRequest.getDefaultLanguage() : "en_US")
                 .defaultCurrency(createUserRequest.getDefaultCurrency()!= null? createUserRequest.getDefaultCurrency() : "USD")
                 .defaultTimezone(createUserRequest.getDefaultTimezone()!= null? createUserRequest.getDefaultTimezone() : "EST")
                 .build();
         up = repo.save(up);
      }



      return Response.ok(up).build();

   }
}
