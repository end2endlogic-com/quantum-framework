package com.e2eq.framework.rest.resources;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/secure")
@Produces(MediaType.APPLICATION_JSON)
public class TestAuthResource {

    private static final Logger log = LoggerFactory.getLogger(TestAuthResource.class);
    @Inject
    SecurityIdentity securityIdentity;

    @GET
    @Path("/authenticated")
    @Authenticated
    public Response authenticatedEndpoint() {
        return Response.ok(new SecureResponse("This endpoint requires authentication")).build();
    }
    @GET
    @Path("/view")
    @RolesAllowed({"user", "admin"})
    public Response viewSecureResource(SecurityContext ctx) {
        Log.info("Context User: " + ctx.getUserPrincipal().getName());
        Log.info(" SecureIdentity: " + securityIdentity.getPrincipal().getName());
        Log.info("Roles: " + securityIdentity.getRoles());
        return Response.ok(new SecureResponse("Secure content viewed")).build();
    }

    @POST
    @Path("/create")
    @RolesAllowed({"admin"})
    public Response createSecureResource() {
        return Response.ok(new SecureResponse("Secure content created")).build();
    }

    @GET
    @Path("/public")
    @PermitAll
    public Response getPublicResource() throws JsonProcessingException {
        return Response.ok(new SecureResponse("Public content")).build();
    }

    @RegisterForReflection
    public record SecureResponse(@JsonProperty("message") String message) {}
}
