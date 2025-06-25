package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.model.security.auth.UserManagement;
import com.e2eq.framework.rest.requests.CreateUserRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/auth")
@Tag(name = "Authentication", description = "Operations related to user authentication and management")
public class AuthResource {

    @Inject
    AuthProviderFactory authProviderFactory;


    @GET
    @Path("/provider/name")
    @Operation(summary = "Get Authentication Provider Name", description = "Returns the name of the current authentication provider.")
    @APIResponse(responseCode = "200", description = "Successfully retrieved prov")
    public Response getProviderName() {
        AuthProvider authProvider = authProviderFactory.getAuthProvider();
        return Response.ok(authProvider.getName()).build();
    }

    @POST
    @Path("/create-user")
    @Operation(summary = "Create User", description = "Creates a new user with the provided details with in the auth provider only")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "User created successfully"),
       @APIResponse(responseCode = "400", description = "Invalid request data")
    })
    public Response createUser(CreateUserRequest request) {
        UserManagement usermanager = authProviderFactory.getUserManager();
        usermanager.createUser(request.getUserId(), request.getPassword(),
           request.getUsername(), request.getRoles(), request.getDomainContext());
        return Response.ok().build();
    }

    @POST
    @Path("/login")
    @Produces("application/json")
    @Operation(summary = "User Login", description = "Authenticates a user and returns access and refresh tokens. this auth is only against the auth provider")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "Login successful"),
       @APIResponse(responseCode = "401", description = "Unauthorized")
    })
    public Response login(@QueryParam("userId") String userId, @QueryParam("password") String password) {
        var authProvider = authProviderFactory.getAuthProvider();
        var loginResponse = authProvider.login(userId, password);

        return Response.ok(Map.of(
            "accessToken", loginResponse.positiveResponse().accessToken(),
            "refreshToken", loginResponse.positiveResponse().refreshToken()
        )).build();
    }

    @POST
    @Path("/refresh")
    @Operation(summary = "Logins with a refresh token", description = "Authenticates a user and returns access and refresh tokens. this auth is only against the auth provider")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "Login successful"),
       @APIResponse(responseCode = "401", description = "Unauthorized")
    })
    public Response refresh(@HeaderParam("Authorization") String refreshToken) {
        var authProvider = authProviderFactory.getAuthProvider();
        if (refreshToken == null || refreshToken.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            // Remove "Bearer " if present
            String token = refreshToken.replace("Bearer ", "");
            AuthProvider.LoginResponse response = authProvider.refreshTokens(token);
            return Response.ok(response).build();
        } catch (Exception e) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
    }
}
