package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.inject.Default;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.Produces;

import java.util.stream.Collectors;
import dev.morphia.mapping.codec.pojo.EntityModel;

@Path("/system")
public class SystemResource {
    @Inject
    @Default
    Datastore datastore;


    @GET
    @Path("/mapping")
    @Produces("application/text")
    public Response mapping() {
        MorphiaDatastore ds = dataStore;
        var entities = ds.getMapper().getMappedEntities();
        var list = entities.stream()
                    .map(EntityModel::getName).sorted()
                    .collect(Collectors.joining(", "));
            return Response.ok(list).build();
    }
}
