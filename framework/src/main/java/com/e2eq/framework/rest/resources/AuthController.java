package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/auth")
public class AuthController {

    @Inject
    AuthProviderFactory authProviderFactory;

    @POST
    @Path("/login")
    @Produces("application/json")
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
