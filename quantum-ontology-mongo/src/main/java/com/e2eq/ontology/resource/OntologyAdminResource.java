package com.e2eq.ontology.resource;

import com.e2eq.ontology.model.OntologyMeta;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import com.e2eq.ontology.service.OntologyReindexer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/ontology/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class OntologyAdminResource {

    @Inject
    OntologyMetaRepo metaRepo;

    @Inject
    OntologyReindexer reindexer;

    @GET
    @Path("/meta")
    public OntologyMeta meta() {
        return metaRepo.getSingleton().orElse(null);
    }

    @POST
    @Path("/reindex")
    public Response triggerReindex(@QueryParam("realm") @DefaultValue("default") String realm) {
        reindexer.runAsync(realm);
        return Response.accepted(Map.of("status", reindexer.status())).build();
    }

    @GET
    @Path("/reindex/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", reindexer.isRunning(),
                "status", reindexer.status()
        );
    }
}
