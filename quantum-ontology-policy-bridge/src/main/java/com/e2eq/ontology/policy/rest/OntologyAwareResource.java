package com.e2eq.ontology.policy.rest;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.rest.resources.BaseResource;
import com.e2eq.ontology.mongo.OntologyContextEnricherMongo;
import com.e2eq.ontology.policy.ListQueryRewriter;
import dev.morphia.Datastore;
import dev.morphia.annotations.Entity;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Abstract base resource that extends {@link BaseResource} to add ontology-aware list and detail
 * endpoints. Resources extending this class expose standard CRUD plus ontology-specific capabilities:
 * <ul>
 *   <li>List endpoint with optional ontology-based filtering (e.g. filter by related customer,
 *       project, or timesheet IDs via the ontology graph)</li>
 *   <li>Detail ontology context endpoint returning ontology edges for a single entity</li>
 *   <li>Optional aggregation and expand for combined attributes spanning collections</li>
 * </ul>
 *
 * <p>See the ontology and REST CRUD user guides for usage and extension guidance.
 *
 * @param <T> the entity type
 * @param <R> the repository type
 */
public abstract class OntologyAwareResource<T extends UnversionedBaseModel, R extends BaseMorphiaRepo<T>> extends BaseResource<T, R> {

    /** Constraint that filters list results by ontology predicate and destination IDs. */
    protected record OntologyConstraint(String predicate, java.util.Collection<String> dstIds) {}

    @Inject
    ListQueryRewriter listQueryRewriter;

    @Inject
    OntologyContextEnricherMongo ontologyContextEnricher;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @ConfigProperty(name = "feature.ontologyList.aggregation.enabled", defaultValue = "false")
    boolean ontologyListAggregationEnabled;

    protected OntologyAwareResource(R repo) {
        super(repo);
    }

    /**
     * Override to allow expand (join) in ontology list responses. When true and expand param is
     * provided, the resource may use aggregation to return combined attributes spanning collections.
     */
    protected boolean supportsExpandInOntologyList() {
        return false;
    }

    /**
     * Creates an ontology constraint for list filtering.
     *
     * @param predicate the ontology property (e.g. "invoicesCustomer", "invoicesProject")
     * @param dstIds    destination entity IDs to filter by
     * @return the constraint, or null if predicate is blank
     */
    protected OntologyConstraint constraint(String predicate, java.util.Collection<String> dstIds) {
        if (predicate == null || predicate.isBlank()) {
            return null;
        }
        return new OntologyConstraint(predicate, dstIds);
    }

    /**
     * Lists entities with optional ontology constraints and expand (join). When expand is provided
     * and supported, uses aggregation to return combined attributes spanning collections.
     *
     * @param expand comma-separated expand paths (e.g. "customerReference,projectReference"), or null
     */
    protected Collection<T> getOntologyList(HttpHeaders headers,
                                            int skip,
                                            int limit,
                                            String filter,
                                            String sort,
                                            String projection,
                                            String expand,
                                            OntologyConstraint... constraints) {
        Optional<Set<String>> maybeIds = resolveIds(constraints);
        if (maybeIds.isPresent()) {
            Set<String> ids = maybeIds.get();
            if (ids.isEmpty()) {
                return emptyCollection(headers, skip, limit, filter);
            }
            String idClause = buildIdClause(ids);
            filter = mergeFilters(filter, idClause);
        }

        String fullFilter = buildFilterWithExpand(expand, filter);
        if (supportsExpandInOntologyList() && expand != null && !expand.isBlank() && ontologyListAggregationEnabled) {
            return tryAggregationList(headers, skip, limit, fullFilter, sort, projection);
        }
        return super.getList(headers, skip, limit, fullFilter, sort, projection);
    }

    /** Backward-compatible overload without expand. */
    protected Collection<T> getOntologyList(HttpHeaders headers,
                                            int skip,
                                            int limit,
                                            String filter,
                                            String sort,
                                            String projection,
                                            OntologyConstraint... constraints) {
        return getOntologyList(headers, skip, limit, filter, sort, projection, null, constraints);
    }

    private String buildFilterWithExpand(String expand, String filter) {
        if (expand == null || expand.isBlank()) {
            return filter;
        }
        String paths = expand.trim();
        if (paths.contains("(") || paths.contains(")")) {
            return filter; // already has expand(...) syntax, use as-is
        }
        String expandClause = "expand(" + paths + ")";
        return mergeFilters(expandClause, filter);
    }

    @SuppressWarnings("unchecked")
    private Collection<T> tryAggregationList(HttpHeaders headers, int skip, int limit, String query, String sort, String projection) {
        List<com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field> sortFields = toPlannerSortFields(sort);
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(query, modelClass, limit, skip, sortFields);
        if (planned.getMode() != PlannerResult.Mode.AGGREGATION) {
            return super.getList(headers, skip, limit, query, sort, projection);
        }
        var pipeline = planned.getAggregation();
        boolean hasUnknownFrom = pipeline.stream()
                .filter(d -> d instanceof org.bson.Document)
                .map(d -> (org.bson.Document) d)
                .filter(doc -> doc.containsKey("$lookup"))
                .map(doc -> doc.get("$lookup", org.bson.Document.class))
                .anyMatch(lookup -> "__unknown__".equals(lookup != null ? lookup.getString("from") : null));
        if (hasUnknownFrom) {
            Log.warnf("Ontology list aggregation skipped: pipeline has unresolved $lookup.from for %s", modelClass.getSimpleName());
            return super.getList(headers, skip, limit, query, sort, projection);
        }
        String realm = resolveRealm(headers);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        String rootCollection = resolveCollectionName(modelClass);
        List<org.bson.conversions.Bson> clean = new ArrayList<>();
        for (org.bson.conversions.Bson s : pipeline) {
            if (s instanceof org.bson.Document d && d.containsKey("$plannedExpandPaths")) {
                continue;
            }
            clean.add(s);
        }
        int effLimit = limit > 0 ? limit : 50;
        int effSkip = Math.max(0, skip);
        List<org.bson.Document> rows = ds.getDatabase()
                .getCollection(rootCollection)
                .aggregate(clean, org.bson.Document.class)
                .into(new ArrayList<>());
        Collection<org.bson.Document> col = new Collection<>(rows, effSkip, effLimit, query, (long) rows.size());
        String realmId = headers.getHeaderString("X-Realm");
        col.setRealm(realmId == null ? repo.getDatabaseName() : realmId);
        @SuppressWarnings("unchecked")
        Collection<T> result = (Collection<T>) (Collection<?>) col;
        return result;
    }

    private List<com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field> toPlannerSortFields(String sort) {
        if (sort == null || sort.isBlank()) return null;
        List<com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field> out = new ArrayList<>();
        for (String part : sort.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dir = p.startsWith("-") ? -1 : 1;
            String name = p.startsWith("-") || p.startsWith("+") ? p.substring(1) : p;
            if (!name.isBlank()) {
                out.add(new com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan.SortSpec.Field(name, dir));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private String resolveRealm(HttpHeaders headers) {
        String realm = headers.getHeaderString("X-Realm");
        if (realm != null && !realm.isBlank()) return realm;
        return SecurityContext.getPrincipalContext()
                .map(pc -> pc.getDefaultRealm())
                .orElse(repo.getDatabaseName());
    }

    private String resolveCollectionName(Class<? extends UnversionedBaseModel> root) {
        Entity e = root.getAnnotation(Entity.class);
        if (e != null && e.value() != null && !e.value().isBlank()) {
            return e.value();
        }
        return root.getSimpleName();
    }

    /**
     * Builds the ontology context response for a single entity (ontology edges).
     *
     * @param headers HTTP headers (for X-Realm, etc.)
     * @param id      the entity ID
     * @return response with enriched ontology payload, or error if entity not found
     */
    protected Response buildOntologyContextResponse(HttpHeaders headers, String id) {
        Response probe = super.byPathId(headers, id);
        if (probe.getStatus() != Response.Status.OK.getStatusCode()) {
            return probe;
        }
        DataDomain dataDomain = getCurrentDataDomain();
        Map<String, Object> payload = ontologyContextEnricher.enrich(dataDomain, id);
        return Response.ok(payload).build();
    }

    private Optional<Set<String>> resolveIds(OntologyConstraint... constraints) {
        if (constraints == null || constraints.length == 0) {
            return Optional.empty();
        }
        DataDomain dataDomain = getCurrentDataDomain();
        Set<String> accumulator = null;
        for (OntologyConstraint constraint : constraints) {
            if (constraint == null) {
                continue;
            }
            java.util.Collection<String> dstIds = constraint.dstIds();
            if (dstIds == null || dstIds.isEmpty()) {
                continue;
            }
            Set<String> matches = listQueryRewriter.idsForHasEdgeAny(dataDomain, constraint.predicate(), dstIds);
            if (accumulator == null) {
                accumulator = new LinkedHashSet<>(matches);
            } else {
                accumulator.retainAll(matches);
            }
            if (accumulator.isEmpty()) {
                break;
            }
        }
        return Optional.ofNullable(accumulator);
    }

    private String buildIdClause(Set<String> ids) {
        List<String> validIds = ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(this::isValidObjectId)
                .collect(Collectors.toList());
        if (validIds.isEmpty() && !ids.isEmpty()) {
            Log.warnf("All %d ontology source IDs failed ObjectId validation; may indicate edges stored with refName", ids.size());
        }
        String joined = String.join(", ", validIds);
        return "id:^[" + joined + "]";
    }

    private boolean isValidObjectId(String id) {
        return id != null && id.matches("^[0-9a-fA-F]{24}$");
    }

    private String mergeFilters(String base, String clause) {
        if (base == null || base.isBlank()) {
            return clause;
        }
        return "(" + base + ") && " + clause;
    }

    private Collection<T> emptyCollection(HttpHeaders headers, int skip, int limit, String filter) {
        Collection<T> collection = new Collection<>(List.of(), skip, limit, filter, 0L);
        String realmId = headers.getHeaderString("X-Realm");
        collection.setRealm(realmId == null ? repo.getDatabaseName() : realmId);
        return collection;
    }

    /** Returns the current tenant ID from security context. */
    protected String currentTenantId() {
        return SecurityContext.getPrincipalContext()
                .map(pc -> pc.getDataDomain().getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant id is not available in the current security context"));
    }

    /** Returns the current data domain from security context. */
    protected DataDomain getCurrentDataDomain() {
        return SecurityContext.getPrincipalDataDomain()
                .orElseThrow(() -> new IllegalStateException("DataDomain is not available in the current security context"));
    }
}
