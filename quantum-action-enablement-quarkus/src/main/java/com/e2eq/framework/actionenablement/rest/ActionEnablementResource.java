package com.e2eq.framework.actionenablement.rest;

import com.e2eq.framework.actionenablement.model.ScopedActionEnablementRequest;
import com.e2eq.framework.actionenablement.model.ScopedActionEnablementResponse;
import com.e2eq.framework.actionenablement.runtime.ScopedActionEnablementService;
import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/system/actions/enablement")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@FunctionalMapping(area = "system", domain = "action-enablement")
public class ActionEnablementResource {

    @Inject
    ScopedActionEnablementService enablementService;

    @POST
    @Path("/check")
    @FunctionalAction("check")
    public Response check(ScopedActionEnablementRequest request) {
        if (request == null || request.getActions() == null || request.getActions().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("actions are required")
                    .build();
        }

        return Response.ok(ScopedActionEnablementResponse.builder()
                .results(enablementService.evaluate(request))
                .build()).build();
    }

    @GET
    @Path("/manifest")
    @FunctionalAction("view")
    public Response manifest() {
        return Response.ok(enablementService.listManifest()).build();
    }
}
