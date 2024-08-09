package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.ChangePasswordRequest;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.security.model.persistent.models.security.CredentialUserIdPassword;
import com.e2eq.framework.security.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.util.EncryptionUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path("/user/credentials")
public class CredentialsResource extends BaseResource<CredentialUserIdPassword, CredentialRepo> {

    CredentialsResource (CredentialRepo repo ) {
        super(repo);
    }

    @Path("changePassword")
    @POST
    @RolesAllowed({ "user", "admin" })
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(SecurityContext securityContext, ChangePasswordRequest changePasswordRequest) {

        if (!changePasswordRequest.getConfirmPassword().equals(changePasswordRequest.getNewPassword())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match password not changed").build();
        }

        // retrieve the record for this user id
        // check if the user is an admin if the user is an admin
        // change the password, if not check that userId is the
        // same as the logged in person
        if (securityContext.isUserInRole("admin")) {
            repo.findByUserId(changePasswordRequest.getUserId())
                    .ifPresent(credentialUserIdPassword -> {
                        credentialUserIdPassword.setPasswordHash(EncryptionUtils.hashPassword(changePasswordRequest.getNewPassword()));
                        repo.save(credentialUserIdPassword);
                    });
        } else if (securityContext.isUserInRole("user")) {
            if (securityContext.getUserPrincipal().getName().equals(changePasswordRequest.getUserId())) {
                repo.findByUserId(changePasswordRequest.getUserId())
                        .ifPresent(credentialUserIdPassword -> {
                            credentialUserIdPassword.setPasswordHash(EncryptionUtils.hashPassword(changePasswordRequest.getNewPassword()));
                            repo.save(credentialUserIdPassword);
                        });
            } else {
                return Response.status(Response.Status.BAD_REQUEST).entity(RestError.builder()
                        .status(Response.Status.BAD_REQUEST.getStatusCode())
                        .statusMessage("Bad Request: User not authorized to change password, UserId was not the principal name")
                        .reasonMessage("User not authorized to change password, UserId was not the principal name")
                        .debugMessage("User not authorized to change password: PrincipalId:" + securityContext.getUserPrincipal().getName() + " passed userId:" + changePasswordRequest.getUserId() + " not matching")
                        .build()
                ).build();

            }
        } else {
            throw new RuntimeException("Bad Request: User is neither an admin or a user aborting");
        }

        return Response.status(Response.Status.OK).entity("Password changed").build();
    }

}
