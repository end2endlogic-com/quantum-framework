package com.e2eq.framework.cli.generated.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import com.e2eq.framework.cli.generated.model.*;

@Produces(MediaType.APPLICATION_JSON)
public interface AuthEndpoint {
    @POST
    @Path("/security/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Valid LoginResponse login(@Valid @NotNull LoginRequest body);

    @POST
    @Path("/security/service-token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Valid ServiceTokenResponse mintServiceToken(@HeaderParam("Authorization") @NotNull String authorization, @Valid @NotNull ServiceTokenRequest body);
}
