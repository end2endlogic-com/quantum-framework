package com.e2eq.framework.rest.resources;


import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Arrays;
import java.util.List;

@Path("/hello")
@FunctionalMapping(area = "SYSTEM", domain="HEARTBEAT")
public class GreetingResource {

    @Inject
    JsonWebToken jwt;


    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @FunctionalAction("HELLO")
    public String hello(@Context UriInfo uriInfo) {
        return "Hello From Server";
    }

    @Path("/list")
    @GET
    @RolesAllowed({ "user", "admin", "system" })
    @FunctionalAction("HELLO_LIST")
    @Produces(MediaType.TEXT_PLAIN)
    public List<String> helloList() {
        return Arrays.asList("Test", "Test1", "Test2");
    }


    @GET
    @Path("/context")
    @FunctionalAction("SECURE_CONTEXT")
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    public String secureContext(@Context SecurityContext ctx) {
        return getResponseString(ctx);
    }

    private String getResponseString(SecurityContext ctx) {
        String name;
        if (ctx.getUserPrincipal() == null || ctx.isUserInRole("ANONYMOUS")) {
            name = "anonymous";
        } else if (!ctx.getUserPrincipal().getName().equals(jwt.getName())) {
            throw new InternalServerErrorException("Principal and JsonWebToken names do not match");
        } else {
            name = ctx.getUserPrincipal().getName();
        }
        if (hasJwt()) {
            for (String claimName: jwt.getClaimNames()) {
                Log.info(claimName + ":" + jwt.getClaim(claimName));
            }
        }
        return String.format("principleName: + %s,"
            + " isHttps: %s,"
            + " authScheme: %s,"
            + " hasJWT: %s"
            + " isUser: %s"
            + " isAdmin: %s",
            name, ctx.isSecure(), ctx.getAuthenticationScheme(), hasJwt(), ctx.isUserInRole("user"), ctx.isUserInRole("admin"));

    }

    private boolean hasJwt() {
	return jwt.getClaimNames() != null;
    }
}
