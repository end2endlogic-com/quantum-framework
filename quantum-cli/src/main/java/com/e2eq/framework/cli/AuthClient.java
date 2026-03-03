package com.e2eq.framework.cli;

import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.rest.models.AuthResponse;
import com.e2eq.framework.rest.requests.ServiceTokenRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/v1/auth")
public interface AuthClient {

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    AuthResponse login(AuthRequest authRequest);

    @POST
    @Path("/service-token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> generateServiceToken(@HeaderParam("Authorization") String bearerToken, ServiceTokenRequest request);
}
