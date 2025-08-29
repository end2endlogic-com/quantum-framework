package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ObjectListRepo;
import com.e2eq.framework.model.persistent.morphia.StaticDynamicListRepo;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.models.SuccessResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class StaticDynamicListResource<
        O extends UnversionedBaseModel,
        OR extends MorphiaRepo<O>,
        T extends StaticDynamicList<O>,
        TR extends ObjectListRepo<O,T, OR>> extends BaseResource<T,TR>{
    protected StaticDynamicListResource(TR repo) {
        super(repo);
    }


    @Path("objects/id/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
            @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    @GET
    public Response getLocationsForLocationListById(@PathParam(value = "id") String id) {
        Objects.requireNonNull(id);
        T locationList = repo.findById(id).orElse(null);
        if (locationList != null) {
            List<O> objects = new ArrayList<>();
            return Response.ok().entity(repo.getObjectsForList(locationList, objects)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @Path("locations/refName/{refName}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
            @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public Response getLocationsForLocationListByRefName(@PathParam(value = "refName") String refName) {
        Objects.requireNonNull(refName);
        T locationList = repo.findByRefName(refName).orElse(null);
        if (locationList != null) {
            List<O> objects = new ArrayList<>();
            return Response.ok().entity(repo.getObjectsForList(locationList, objects)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }


}
