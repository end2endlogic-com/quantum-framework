package com.e2eq.framework.oauth.resource;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth 2.0 / OIDC UserInfo endpoint.
 * Returns claims about the authenticated user based on the access token.
 */
@Path("/oauth/userinfo")
@Authenticated
public class OAuthUserInfoResource {

    @Inject
    JsonWebToken jwt;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response userinfo() {
        if (jwt.getSubject() == null) {
            return Response.status(401)
                    .entity(Map.of("error", "invalid_token", "error_description", "Token has no subject"))
                    .build();
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", jwt.getSubject());

        if (jwt.getIssuer() != null) {
            claims.put("iss", jwt.getIssuer());
        }
        if (jwt.getClaim("email") != null) {
            claims.put("email", jwt.getClaim("email"));
        }
        if (jwt.getClaim("preferred_username") != null) {
            claims.put("preferred_username", jwt.getClaim("preferred_username"));
        }
        if (jwt.getGroups() != null && !jwt.getGroups().isEmpty()) {
            claims.put("groups", jwt.getGroups());
        }

        return Response.ok(claims).build();
    }
}
