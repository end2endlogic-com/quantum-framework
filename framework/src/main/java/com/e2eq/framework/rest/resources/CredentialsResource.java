package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.ChangePasswordRequest;
import com.e2eq.framework.rest.models.FileUpload;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.util.EncryptionUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/user/credentials")
@RolesAllowed({ "user", "admin" })
@Tag(name = "user", description = "Operations related to managing users")
public class CredentialsResource extends BaseResource<CredentialUserIdPassword, CredentialRepo> {

    CredentialsResource (CredentialRepo repo ) {
        super(repo);
    }



    @Override
    @RolesAllowed({ "admin" })
    public Response importCSVList(
            @Context UriInfo info,
            @BeanParam FileUpload fileUpload,
            @Parameter(description = "The character that must be used to separate fields of the same record")
            @QueryParam("fieldSeparator") @DefaultValue(",") String fieldSeparator,
            @Parameter(description = "The choice of strategy for quoting columns. One of \"QUOTE_WHERE_ESSENTIAL\" or \"QUOTE_ALL_COLUMNS\"")
            @QueryParam("quotingStrategy") @DefaultValue("QUOTE_WHERE_ESSENTIAL") String quotingStrategy,
            @Parameter(description = "The character that is used to surround the values of specific (or all) fields")
            @QueryParam("quoteChar") @DefaultValue("\"") String quoteChar,
            @Parameter(description = "Whether to skip the header row in the CSV file")
            @QueryParam("skipHeaderRow") @DefaultValue("true") boolean skipHeaderRow,
            @Parameter(description = "The charset encoding to use for the file")
            @QueryParam("charsetEncoding") @DefaultValue("UTF-8-without-BOM") String charsetEncoding,
            @Parameter(description = "A non-empty list of the names of the columns expected in the CSV file that map to the model fields")
            @QueryParam("requestedColumns") List<String> requestedColumns) {
        return super.importCSVList(info, fileUpload, fieldSeparator,quotingStrategy, quoteChar, skipHeaderRow, charsetEncoding, requestedColumns);
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
