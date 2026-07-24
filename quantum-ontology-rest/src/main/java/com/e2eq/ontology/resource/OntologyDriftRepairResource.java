package com.e2eq.ontology.resource;

import com.e2eq.ontology.service.DriftRepairJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * Admin endpoint to run drift-repair per realm and entity class with pagination.
 */
@Path("/ontology/admin/drift-repair")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OntologyDriftRepairResource {

    @Inject
    DriftRepairJob job;

    @POST
    public DriftRepairJob.Result run(DriftRepairJob.Request req,
                                     @QueryParam("realm") String realm,
                                     @QueryParam("entityClass") String entityClass,
                                     @QueryParam("pageSize") Integer pageSize,
                                     @QueryParam("pageToken") String pageToken,
                                     @QueryParam("prune") @DefaultValue("true") boolean prune,
                                     @QueryParam("derive") @DefaultValue("true") boolean derive,
                                     @QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        // Allow both JSON body and query params; query params override if present.
        if (req == null) req = new DriftRepairJob.Request();
        if (realm != null) req.realmId = realm;
        if (entityClass != null) req.entityClass = entityClass;
        if (pageSize != null) req.pageSize = pageSize;
        if (pageToken != null) req.pageToken = pageToken;
        req.prune = prune;
        req.derive = derive;
        req.dryRun = dryRun;
        return job.run(req);
    }
}
