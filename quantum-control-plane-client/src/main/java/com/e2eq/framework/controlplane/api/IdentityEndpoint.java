package com.e2eq.framework.controlplane.api;

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
import com.e2eq.framework.controlplane.model.*;

@Produces(MediaType.APPLICATION_JSON)
public interface IdentityEndpoint {
    @GET
    @Path("/control/identity/flat-roles")
    @Valid SubjectFlatRolesEntry flatRolesForSubject(@QueryParam("subject") @NotNull String subject);
}
