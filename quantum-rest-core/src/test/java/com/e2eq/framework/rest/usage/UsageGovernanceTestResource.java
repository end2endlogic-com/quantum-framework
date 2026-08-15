package com.e2eq.framework.rest.usage;

import com.e2eq.framework.annotations.FunctionalMapping;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/__quantum-usage-test")
@FunctionalMapping(area = "SYSTEM", domain = "USAGE_TEST")
public class UsageGovernanceTestResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "quantumUsageTest", summary = "Usage-governance integration test endpoint")
    public String get() {
        return "ok";
    }

    @GET
    @Path("/public")
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "quantumUsagePublicTest", summary = "Public endpoint excluded from post-auth governance")
    public String publicEndpoint() {
        return "public";
    }
}
