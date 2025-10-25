package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
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
 * - POST /api/query/find: executes query in FILTER mode and returns Collection
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
        // Build planned query, passing paging/sort through so the compiler can emit root stages
        java.util.List<LogicalPlan.SortSpec.Field> sortFields = null;
        if (req.sort != null && !req.sort.isEmpty()) {
            sortFields = new java.util.ArrayList<>();
            for (SortSpec s : req.sort) {
                if (s == null || s.field == null || s.field.isBlank()) continue;
                int dir = (s.dir != null && s.dir.equalsIgnoreCase("DESC")) ? -1 : 1;
                sortFields.add(new com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field(s.field, dir));
            }
        }
        Integer limit = (req.page != null) ? req.page.limit : null;
        Integer skip = (req.page != null) ? req.page.skip : null;
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(req.query, root, limit, skip, sortFields);
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
            // Execute aggregation pipeline against the root collection
            String realm = (req.realm == null || req.realm.isBlank()) ? defaultRealm : req.realm;
            Datastore ds = morphiaDataStore.getDataStore(realm);
            String rootCollection = resolveCollectionName(root);

            // Strip out non-executable marker stages and build a clean pipeline
            List<org.bson.conversions.Bson> clean = new java.util.ArrayList<>();
            for (org.bson.conversions.Bson s : pipeline) {
                if (s instanceof org.bson.Document d && d.containsKey("$plannedExpandPaths")) {
                    continue; // drop marker
                }
                clean.add(s);
            }

            // Determine paging values for the Collection envelope
            int effLimit = (limit != null) ? limit : 50;
            int effSkip = (skip != null) ? skip : 0;

            // Run aggregation and wrap results
            List<org.bson.Document> rows = ds.getDatabase()
                    .getCollection(rootCollection)
                    .aggregate(clean, org.bson.Document.class)
                    .into(new java.util.ArrayList<>());
            Collection<org.bson.Document> col = new Collection<>(rows, effSkip, effLimit, req.query);
            return Response.ok(col).build();
        }
        // FILTER path using Morphia
        String realm = (req.realm == null || req.realm.isBlank()) ? defaultRealm : req.realm;
        Datastore ds = morphiaDataStore.getDataStore(realm);
        Query<? extends UnversionedBaseModel> q = ds.find(root);
        if (planned.getFilter() != null) {
            q = q.filter(planned.getFilter());
        }
        int fLimit = req.page != null && req.page.limit != null ? req.page.limit : 50;
        int fSkip = req.page != null && req.page.skip != null ? req.page.skip : 0;
        FindOptions fo = new FindOptions().limit(fLimit).skip(fSkip);
        List<?> rows = q.iterator(fo).toList();
        Collection<?> col = new Collection<>(rows, fSkip, fLimit, req.query);
        return Response.ok(col).build();
    }

    private String resolveCollectionName(Class<? extends UnversionedBaseModel> root) {
        dev.morphia.annotations.Entity e = root.getAnnotation(dev.morphia.annotations.Entity.class);
        if (e != null && e.value() != null && !e.value().isBlank()) {
            return e.value();
        }
        return root.getSimpleName();
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
        public java.util.List<SortSpec> sort; // optional; if present, applied; query string sort takes precedence when parsed in future
    }
    @RegisterForReflection
    public static class Page { public Integer limit; public Integer skip; }
    @RegisterForReflection
    public static class SortSpec { public String field; public String dir; } // dir: ASC|DESC
}
