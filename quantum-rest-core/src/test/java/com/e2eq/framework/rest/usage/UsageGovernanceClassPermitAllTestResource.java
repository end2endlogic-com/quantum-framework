package com.e2eq.framework.rest.usage;

import com.e2eq.framework.annotations.FunctionalMapping;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/__quantum-usage-class-public-test")
@PermitAll
@FunctionalMapping(area = "SYSTEM", domain = "USAGE_TEST")
public class UsageGovernanceClassPermitAllTestResource {

    @GET
    @Path("/public")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "quantumUsageClassPublicTest")
    public String publicEndpoint() {
        return "public";
    }

    @GET
    @Path("/authenticated")
    @Authenticated
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "quantumUsageClassAuthenticatedTest")
    public String authenticatedEndpoint() {
        return "authenticated";
    }
}
