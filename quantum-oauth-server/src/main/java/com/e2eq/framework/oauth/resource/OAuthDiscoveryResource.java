package com.e2eq.framework.oauth.resource;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenID Connect Discovery endpoint.
 * Returns the provider metadata so OAuth clients can auto-configure.
 */
@Path("/.well-known")
@PermitAll
public class OAuthDiscoveryResource {

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @GET
    @Path("/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> discovery(@Context UriInfo uriInfo) {
        String baseUrl = issuer;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer);
        config.put("authorization_endpoint", baseUrl + "/oauth/authorize");
        config.put("token_endpoint", baseUrl + "/oauth/token");
        config.put("userinfo_endpoint", baseUrl + "/oauth/userinfo");
        config.put("jwks_uri", baseUrl + "/oauth/jwks");

        config.put("response_types_supported", List.of("code"));
        config.put("grant_types_supported", List.of(
                "authorization_code", "client_credentials", "refresh_token"));
        config.put("subject_types_supported", List.of("public"));
        config.put("id_token_signing_alg_values_supported", List.of("RS256"));
        config.put("scopes_supported", List.of("openid", "profile", "email"));
        config.put("token_endpoint_auth_methods_supported", List.of(
                "client_secret_basic", "client_secret_post"));
        config.put("code_challenge_methods_supported", List.of("S256"));
        config.put("claims_supported", List.of(
                "sub", "iss", "aud", "exp", "iat", "email", "preferred_username", "groups"));

        return config;
    }
}
