package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import com.e2eq.framework.model.persistent.morphia.query.QueryGateway;
import com.e2eq.framework.model.persistent.morphia.query.QueryGatewayImpl;
import com.e2eq.framework.rest.models.Collection;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;

/**
 * REST facade for the planner-driven query path.
 * - POST /api/query/plan: returns planner mode and expand paths.
 * - POST /api/query/find: executes query in FILTER mode and returns Collection<T>.
 *   If AGGREGATION mode is selected, returns 501 until aggregation compiler is implemented.
 */
@Path("/api/query")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class QueryGatewayResource {

    private final QueryGateway gateway = new QueryGatewayImpl();

    @Inject
    MorphiaDataStore morphiaDataStore;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    @POST
    @Path("/plan")
    public PlanResponse plan(PlanRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        PlannerResult pr = gateway.plan(req.query, root);
        PlanResponse out = new PlanResponse();
        out.mode = pr.getMode().name();
        out.expandPaths = pr.getExpandPaths();
        return out;
    }

    @ConfigProperty(name = "feature.queryGateway.execution.enabled", defaultValue = "false")
    boolean aggregationExecutionEnabled;

    @POST
    @Path("/find")
    public Response find(FindRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(req.query, root);
        if (planned.getMode() == PlannerResult.Mode.AGGREGATION) {
            if (!aggregationExecutionEnabled) {
                Map<String, Object> body = new HashMap<>();
                body.put("error", "NotImplemented");
                body.put("message", "Aggregation execution is disabled. Enable feature.queryGateway.execution.enabled to execute expand(...)");
                return Response.status(Response.Status.NOT_IMPLEMENTED).entity(body).build();
            }
            // Execution is enabled, but ensure the pipeline is executable
            var pipeline = planned.getAggregation();
            boolean hasUnknownFrom = pipeline.stream()
                    .filter(d -> d instanceof org.bson.Document)
                    .map(d -> (org.bson.Document) d)
                    .filter(doc -> doc.containsKey("$lookup"))
                    .map(doc -> doc.get("$lookup", org.bson.Document.class))
                    .anyMatch(lookup -> "__unknown__".equals(lookup.getString("from")));
            if (hasUnknownFrom) {
                Map<String, Object> body = new HashMap<>();
                body.put("error", "UnresolvedCollection");
                body.put("message", "Aggregation pipeline contains an unresolved $lookup.from. MetadataRegistry must resolve target collections.");
                return Response.status(422).entity(body).build();
            }
            // Until full execution wiring is implemented, return 501 to avoid partial behavior
            Map<String, Object> body = new HashMap<>();
            body.put("error", "NotImplemented");
            body.put("message", "Aggregation execution path is under construction. Use /plan for now.");
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity(body).build();
        }
        // FILTER path using Morphia
        String realm = (req.realm == null || req.realm.isBlank()) ? defaultRealm : req.realm;
        Datastore ds = morphiaDataStore.getDataStore(realm);
        Query<? extends UnversionedBaseModel> q = ds.find(root);
        if (planned.getFilter() != null) {
            q = q.filter(planned.getFilter());
        }
        int limit = req.page != null && req.page.limit != null ? req.page.limit : 50;
        int skip = req.page != null && req.page.skip != null ? req.page.skip : 0;
        FindOptions fo = new FindOptions().limit(limit).skip(skip);
        List<?> rows = q.iterator(fo).toList();
        Collection<?> col = new Collection<>(rows, skip, limit, req.query);
        return Response.ok(col).build();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends UnversionedBaseModel> resolveRoot(String rootType) {
        if (rootType == null || rootType.isBlank()) {
            throw new BadRequestException("rootType is required");
        }
        try {
            Class<?> c = Class.forName(rootType);
            if (!UnversionedBaseModel.class.isAssignableFrom(c)) {
                throw new BadRequestException("rootType must extend UnversionedBaseModel: " + rootType);
            }
            return (Class<? extends UnversionedBaseModel>) c;
        } catch (ClassNotFoundException e) {
            Log.errorf(e, "Unknown rootType %s", rootType);
            throw new BadRequestException("Unknown rootType: " + rootType);
        }
    }

    // DTOs
    @RegisterForReflection
    public static class PlanRequest { public String rootType; public String query; }
    @RegisterForReflection
    public static class PlanResponse { public String mode; public List<String> expandPaths; }

    @RegisterForReflection
    public static class FindRequest {
        public String rootType;
        public String query;
        public Page page;
        public String realm; // optional; if absent default realm is used
    }
    @RegisterForReflection
    public static class Page { public Integer limit; public Integer skip; }
}
