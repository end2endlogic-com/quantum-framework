package com.e2eq.framework.rest.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/** Needed to add security auth to resources for swagger-ui **/
@SecurityScheme(
    securitySchemeName = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
@Path("/secure")
public class TestSecureResource {

    @GET
    @Path("/hello")
    @SecurityRequirement(name = "bearerAuth") // This requires the bearer token
    public Response hello() {
        return Response.ok("Hello, secured endpoint!").build();
    }
}
