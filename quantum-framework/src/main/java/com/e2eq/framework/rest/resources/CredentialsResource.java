package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.rest.models.ChangePasswordRequest;
import com.e2eq.framework.rest.models.FileUpload;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;

@Path("/user/credentials")
@RolesAllowed({ "user", "admin", "system" })
@Tag(name = "user", description = "Operations related to managing users")
public class CredentialsResource extends BaseResource<CredentialUserIdPassword, CredentialRepo> {

    @Inject
    AuthProviderFactory authProviderFactory;

    CredentialsResource (CredentialRepo repo ) {
        super(repo);
    }



    @Override
    @RolesAllowed({ "admin", "system" })
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
    @RolesAllowed({ "user", "admin", "system" })
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changePassword(SecurityContext securityContext,
                                   ChangePasswordRequest changePasswordRequest,
                                   @QueryParam("provider") @DefaultValue("") String provider) {

        if (!changePasswordRequest.getConfirmPassword().equals(changePasswordRequest.getNewPassword())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Passwords do not match password not changed").build();
        }

        if (!securityContext.isUserInRole("admin") && !securityContext.isUserInRole("system") && securityContext.isUserInRole("user")) {
            if (!securityContext.getUserPrincipal().getName().equals(changePasswordRequest.getUserId())) {
                return Response.status(Response.Status.BAD_REQUEST).entity(RestError.builder()
                        .status(Response.Status.BAD_REQUEST.getStatusCode())
                        .statusMessage("Bad Request: User not authorized to change password, UserId was not the principal name")
                        .reasonMessage("User not authorized to change password, UserId was not the principal name")
                        .debugMessage("User not authorized to change password: PrincipalId:" + securityContext.getUserPrincipal().getName() + " passed userId:" + changePasswordRequest.getUserId() + " not matching")
                        .build()
                ).build();
            }
        } else if (!securityContext.isUserInRole("admin") && !securityContext.isUserInRole("system") && !securityContext.isUserInRole("user")) {
            throw new RuntimeException("Bad Request: User is neither an admin or a user aborting");
        }

        String requestedProvider = provider == null || provider.isBlank()
                ? changePasswordRequest.getAuthProvider()
                : provider;

        try {
            UserManagement userManager = resolveUserManager(changePasswordRequest.getUserId(), requestedProvider);
            userManager.changePassword(
                    changePasswordRequest.getUserId(),
                    changePasswordRequest.getOldPassword(),
                    changePasswordRequest.getNewPassword(),
                    false
            );
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid auth provider configuration: " + e.getMessage())
                    .build();
        }

        return Response.status(Response.Status.OK).entity("Password changed").build();
    }

    private UserManagement resolveUserManager(String userId, String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            return authProviderFactory.getUserManager(requestedProvider);
        }

        Optional<CredentialUserIdPassword> credentialOptional = repo.findByUserId(userId);
        if (credentialOptional.isPresent()) {
            CredentialUserIdPassword credential = credentialOptional.get();
            if (credential.getCredentialType() != null && credential.getCredentialType() != CredentialType.PASSWORD) {
                return authProviderFactory.getUserManager();
            }

            if (credential.getIssuer() != null && !credential.getIssuer().isBlank()) {
                AuthProvider issuerProvider = authProviderFactory.getProviderForIssuer(credential.getIssuer());
                if (issuerProvider instanceof UserManagement userManagement) {
                    return userManagement;
                }
            }

            if (credential.getAuthProviderName() != null && !credential.getAuthProviderName().isBlank()) {
                return authProviderFactory.getUserManager(credential.getAuthProviderName());
            }
        }

        return authProviderFactory.getUserManager();
    }

    @GET
    @Path("matching-realms")
    @RolesAllowed({ "user", "admin", "system" })
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchingRealms() {
        List<String> realms = repo.getMatchingRealmsForCurrentUser();
        return Response.ok(realms).build();
    }
}
