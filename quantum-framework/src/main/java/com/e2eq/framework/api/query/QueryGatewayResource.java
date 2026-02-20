package com.e2eq.framework.api.query;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.DeleteValidationService;
import com.e2eq.framework.model.persistent.morphia.ImportSessionRowRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.planner.LogicalPlan;
import com.e2eq.framework.model.persistent.morphia.planner.PlannedQuery;
import com.e2eq.framework.model.persistent.morphia.planner.PlannerResult;
import com.e2eq.framework.model.persistent.morphia.query.QueryGateway;
import com.e2eq.framework.model.persistent.morphia.query.QueryGatewayImpl;
import com.e2eq.framework.model.persistent.imports.ImportSessionRow;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.util.CSVExportHelper;
import com.e2eq.framework.util.CSVImportHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * REST facade for generic entity operations via a unified query API.
 *
 * <p>This resource provides CRUD operations for any Morphia-mapped entity type
 * using dynamic type resolution. Entity types can be specified by their simple name
 * (e.g., "Location") or fully qualified class name (e.g., "com.example.models.Location").</p>
 *
 * <h2>Available Endpoints</h2>
 * <ul>
 *   <li><b>GET /api/query/rootTypes</b> - Lists all available entity types</li>
 *   <li><b>POST /api/query/plan</b> - Returns query execution plan (FILTER vs AGGREGATION mode)</li>
 *   <li><b>POST /api/query/find</b> - Executes a query and returns matching entities</li>
 *   <li><b>POST /api/query/save</b> - Saves (inserts or updates) an entity</li>
 *   <li><b>POST /api/query/delete</b> - Deletes an entity by ID</li>
 *   <li><b>POST /api/query/deleteMany</b> - Deletes multiple entities matching a query</li>
 * </ul>
 *
 * <h2>Query Syntax</h2>
 * <p>Queries use the BIAPI query syntax, supporting:</p>
 * <ul>
 *   <li>Field matching: {@code status:active}, {@code name:"John Doe"}</li>
 *   <li>Wildcards: {@code name:*Smith*}</li>
 *   <li>Comparisons: {@code age>21}, {@code createdAt>=2024-01-01}</li>
 *   <li>Logical operators: {@code &&}, {@code ||}</li>
 *   <li>Expand (joins): {@code expand(customer) && status:active}</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // List available types
 * GET /api/query/rootTypes
 *
 * // Find entities
 * POST /api/query/find
 * { "rootType": "Location", "query": "status:ACTIVE", "page": { "limit": 10 } }
 *
 * // Save an entity
 * POST /api/query/save
 * { "rootType": "Location", "entity": { "refName": "LOC-001", "name": "Warehouse" } }
 *
 * // Delete by ID
 * POST /api/query/delete
 * { "rootType": "Location", "id": "507f1f77bcf86cd799439011" }
 *
 * // Delete by query
 * POST /api/query/deleteMany
 * { "rootType": "Location", "query": "status:INACTIVE" }
 * }</pre>
 */
@Path("/api/query")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@FunctionalMapping(area="integration",domain="query" )
public class QueryGatewayResource {

    private final QueryGateway gateway = new QueryGatewayImpl();

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    DeleteValidationService deleteValidationService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Instance<BaseMorphiaRepo<? extends UnversionedBaseModel>> allRepos;

    @Inject
    CSVImportHelper csvImportHelper;

    @Inject
    ImportSessionRowRepo importSessionRowRepo;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    /** Maximum number of entities to validate and delete in one deleteMany request. */
    @ConfigProperty(name = "quantum.queryGateway.deleteMany.maxMatches", defaultValue = "2000")
    int maxDeleteManyMatches;

    /** Row count threshold: results at or below this are returned inline; above are written to a file. */
    @ConfigProperty(name = "quantum.queryGateway.export.inlineThreshold", defaultValue = "10000")
    int exportInlineThreshold;

    @POST
    @Path("/plan")
    @FunctionalAction("plan")
    public PlanResponse plan(PlanRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        PlannerResult pr = gateway.plan(req.query, root);
        PlanResponse out = new PlanResponse();
        out.mode = pr.getMode().name();
        out.expandPaths = pr.getExpandPaths();
        return out;
    }

    /**
     * Returns a list of valid rootType values that can be used with /plan and /find endpoints.
     * Each entry includes the fully qualified class name, simple name, and collection name.
     *
     * @return list of available root types with metadata
     */
    @GET
    @Path("/rootTypes")
    @FunctionalAction("listRootTypes")
    public RootTypesResponse listRootTypes() {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(defaultRealm);
        Mapper mapper = ds.getMapper();

        List<RootTypeInfo> rootTypes = new ArrayList<>();
        for (EntityModel em : mapper.getMappedEntities()) {
            Class<?> type = em.getType();
            // Only include classes that extend UnversionedBaseModel
            if (UnversionedBaseModel.class.isAssignableFrom(type)) {
                RootTypeInfo info = new RootTypeInfo();
                info.className = type.getName();
                info.simpleName = type.getSimpleName();
                info.collectionName = em.collectionName();
                rootTypes.add(info);
            }
        }

        // Sort by simple name for easier reading
        rootTypes.sort(Comparator.comparing(r -> r.simpleName));

        RootTypesResponse response = new RootTypesResponse();
        response.rootTypes = rootTypes;
        response.count = rootTypes.size();
        return response;
    }

    @ConfigProperty(name = "feature.queryGateway.execution.enabled", defaultValue = "false")
    boolean aggregationExecutionEnabled;

    @POST
    @Path("/find")
    @FunctionalAction("find")
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
        Map<String, String> variableMap = variableMapForQuery(req.realm);
        PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(req.query, root, limit, skip, sortFields, variableMap);
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
            String realm = resolveRealm(req.realm);
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
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
        String realm = resolveRealm(req.realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
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

    // ========================================================================
    // COUNT ENDPOINT
    // ========================================================================

    @POST
    @Path("/count")
    @FunctionalAction("count")
    public Response count(CountRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        String realm = resolveRealm(req.realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);

        try {
            Query<? extends UnversionedBaseModel> q = ds.find(root);

            if (req.query != null && !req.query.isBlank()) {
                Map<String, String> variableMap = variableMapForQuery(req.realm);
                PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(req.query, root, null, null, null, variableMap);
                if (planned.getMode() == PlannerResult.Mode.AGGREGATION) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "InvalidQuery");
                    error.put("message", "Count queries cannot use expand() - only simple filter queries are supported");
                    return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
                }
                if (planned.getFilter() != null) {
                    q = q.filter(planned.getFilter());
                }
            }

            long count = q.count();

            CountResponse response = new CountResponse();
            response.count = count;
            response.rootType = root.getName();
            response.query = req.query;
            return Response.ok(response).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to count entities of type %s", root.getName());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "CountFailed");
            error.put("message", "Failed to count entities: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    // ========================================================================
    // SAVE ENDPOINT
    // ========================================================================

    /**
     * Saves (inserts or updates) an entity of the specified rootType.
     *
     * <p>If the entity has an _id field, it will be updated if it exists or inserted if not.
     * If no _id is provided, a new entity will be inserted.</p>
     *
     * <p>Example request:</p>
     * <pre>{@code
     * POST /api/query/save
     * {
     *   "rootType": "Location",
     *   "realm": "my-tenant",
     *   "entity": {
     *     "refName": "LOC-001",
     *     "name": "Main Warehouse",
     *     "status": "ACTIVE"
     *   }
     * }
     * }</pre>
     *
     * @param req the save request containing rootType, optional realm, and entity data
     * @return the saved entity with its generated/updated _id
     */
    @POST
    @Path("/save")
    @FunctionalAction("save")
    public Response save(SaveRequest req) {
        if (req.entity == null) {
            throw new BadRequestException("entity is required");
        }

        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        String realm = resolveRealm(req.realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);

        try {
            // Convert the Map to the target entity type
            UnversionedBaseModel entity = objectMapper.convertValue(req.entity, root);

            // Save (insert or update)
            UnversionedBaseModel saved = ds.save(entity);

            SaveResponse response = new SaveResponse();
            response.id = saved.getId() != null ? saved.getId().toHexString() : null;
            response.entity = objectMapper.convertValue(saved, Map.class);
            response.rootType = root.getName();

            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            Log.errorf(e, "Failed to convert entity to type %s", root.getName());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "InvalidEntity");
            error.put("message", "Failed to convert entity to " + root.getSimpleName() + ": " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to save entity of type %s", root.getName());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "SaveFailed");
            error.put("message", "Failed to save entity: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    // ========================================================================
    // DELETE ENDPOINTS
    // ========================================================================

    /**
     * Deletes an entity by its ID.
     *
     * <p>Example request:</p>
     * <pre>{@code
     * DELETE /api/query/delete
     * {
     *   "rootType": "Location",
     *   "realm": "my-tenant",
     *   "id": "507f1f77bcf86cd799439011"
     * }
     * }</pre>
     *
     * @param req the delete request containing rootType, optional realm, and entity id
     * @return success status with deleted count
     */
    @POST
    @Path("/delete")
    @FunctionalAction("delete")
    public Response delete(DeleteRequest req) {
        if (req.id == null || req.id.isBlank()) {
            throw new BadRequestException("id is required");
        }

        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        String realm = resolveRealm(req.realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);

        try {
            ObjectId objectId = new ObjectId(req.id);
            Query<? extends UnversionedBaseModel> query = ds.find(root).filter(Filters.eq("_id", objectId));
            UnversionedBaseModel entity = query.first();
            if (entity == null) {
                DeleteResponse notFound = new DeleteResponse();
                notFound.deletedCount = 0;
                notFound.id = req.id;
                notFound.rootType = root.getName();
                notFound.success = false;
                return Response.status(Response.Status.NOT_FOUND).entity(notFound).build();
            }
            deleteValidationService.validateBeforeDelete(realm, entity);
            var result = query.delete();

            DeleteResponse response = new DeleteResponse();
            response.deletedCount = result.getDeletedCount();
            response.id = req.id;
            response.rootType = root.getName();
            response.success = result.getDeletedCount() > 0;
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            Log.errorf(e, "Invalid ObjectId format: %s", req.id);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "InvalidId");
            error.put("message", "Invalid ObjectId format: " + req.id);
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        } catch (ReferentialIntegrityViolationException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ReferentialIntegrity");
            error.put("message", e.getMessage());
            return Response.status(409).entity(error).build();
        } catch (RuntimeException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "DeleteBlocked");
            error.put("message", e.getMessage());
            return Response.status(409).entity(error).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to delete entity of type %s with id %s", root.getName(), req.id);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "DeleteFailed");
            error.put("message", "Failed to delete entity: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    /**
     * Deletes multiple entities matching a query.
     *
     * <p>Example request:</p>
     * <pre>{@code
     * POST /api/query/deleteMany
     * {
     *   "rootType": "Location",
     *   "realm": "my-tenant",
     *   "query": "status:INACTIVE"
     * }
     * }</pre>
     *
     * @param req the delete request containing rootType, optional realm, and query
     * @return success status with deleted count
     */
    @POST
    @Path("/deleteMany")
    @FunctionalAction("deleteMany")
    public Response deleteMany(DeleteManyRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        String realm = resolveRealm(req.realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);

        try {
            Query<? extends UnversionedBaseModel> query = ds.find(root);

            // Apply query filter if provided
            if (req.query != null && !req.query.isBlank()) {
                Map<String, String> variableMap = variableMapForQuery(req.realm);
                PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(req.query, root, null, null, null, variableMap);
                if (planned.getMode() == PlannerResult.Mode.AGGREGATION) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "InvalidQuery");
                    error.put("message", "Delete queries cannot use expand() - only simple filter queries are supported");
                    return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
                }
                if (planned.getFilter() != null) {
                    query = query.filter(planned.getFilter());
                }
            }

            // Load matching entities (capped) so we can validate each before delete
            List<? extends UnversionedBaseModel> toDelete = query
                    .iterator(new FindOptions().limit(maxDeleteManyMatches + 1))
                    .toList();
            if (toDelete.size() > maxDeleteManyMatches) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "TooManyMatches");
                error.put("message", "Query matches more than " + maxDeleteManyMatches + " entities. Narrow the filter or increase quantum.queryGateway.deleteMany.maxMatches.");
                return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
            }
            for (UnversionedBaseModel entity : toDelete) {
                deleteValidationService.validateBeforeDelete(realm, entity);
            }
            if (toDelete.isEmpty()) {
                DeleteManyResponse empty = new DeleteManyResponse();
                empty.deletedCount = 0;
                empty.query = req.query;
                empty.rootType = root.getName();
                empty.success = true;
                return Response.ok(empty).build();
            }
            var ids = toDelete.stream().map(UnversionedBaseModel::getId).filter(java.util.Objects::nonNull).toList();
            var result = ds.find(root).filter(Filters.in("_id", ids)).delete(new dev.morphia.DeleteOptions().multi(true));

            DeleteManyResponse response = new DeleteManyResponse();
            response.deletedCount = result.getDeletedCount();
            response.query = req.query;
            response.rootType = root.getName();
            response.success = true;

            return Response.ok(response).build();
        } catch (ReferentialIntegrityViolationException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ReferentialIntegrity");
            error.put("message", e.getMessage());
            return Response.status(409).entity(error).build();
        } catch (RuntimeException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "DeleteBlocked");
            error.put("message", e.getMessage());
            return Response.status(409).entity(error).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to delete entities of type %s with query %s", root.getName(), req.query);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "DeleteFailed");
            error.put("message", "Failed to delete entities: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    // ========================================================================
    // IMPORT / EXPORT ENDPOINTS
    // ========================================================================

    /**
     * Resolves a BaseMorphiaRepo for the given entity class by iterating all CDI-managed repos.
     */
    @SuppressWarnings("unchecked")
    private <T extends UnversionedBaseModel> BaseMorphiaRepo<T> resolveRepo(Class<T> rootClass) {
        for (BaseMorphiaRepo<? extends UnversionedBaseModel> repo : allRepos) {
            if (repo.getPersistentClass() != null && repo.getPersistentClass().equals(rootClass)) {
                return (BaseMorphiaRepo<T>) repo;
            }
        }
        throw new BadRequestException("No repository found for type: " + rootClass.getName()
                + ". Import/export requires a registered MorphiaRepo for this entity type.");
    }

    /**
     * Analyze a CSV for import. Creates an import session with a preview of rows,
     * their intents (INSERT/UPDATE/SKIP), and any validation errors.
     *
     * <p>Provide CSV data either inline via {@code csvContent} or as a server-side
     * file path via {@code csvFilePath}. Exactly one must be specified.</p>
     */
    @POST
    @Path("/import/analyze")
    @FunctionalAction("importAnalyze")
    public Response importAnalyze(ImportAnalyzeRequest req) {
        if (req.columns == null || req.columns.isEmpty()) {
            throw new BadRequestException("columns is required (ordered list of entity field names matching CSV columns)");
        }
        boolean hasContent = req.csvContent != null && !req.csvContent.isBlank();
        boolean hasFile = req.csvFilePath != null && !req.csvFilePath.isBlank();
        if (!hasContent && !hasFile) {
            throw new BadRequestException("Either csvContent or csvFilePath is required");
        }
        if (hasContent && hasFile) {
            throw new BadRequestException("Provide either csvContent or csvFilePath, not both");
        }

        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        @SuppressWarnings("unchecked")
        BaseMorphiaRepo<UnversionedBaseModel> repo = resolveRepo((Class<UnversionedBaseModel>) root);

        char fieldSep = (req.fieldSeparator != null && !req.fieldSeparator.isEmpty()) ? req.fieldSeparator.charAt(0) : ',';
        char quoteChar = (req.quoteChar != null && !req.quoteChar.isEmpty()) ? req.quoteChar.charAt(0) : '"';
        Charset charset = (req.charset != null && !req.charset.isBlank()) ? Charset.forName(req.charset) : StandardCharsets.UTF_8;

        try {
            InputStream inputStream;
            if (hasContent) {
                inputStream = new ByteArrayInputStream(req.csvContent.getBytes(charset));
            } else {
                File csvFile = new File(req.csvFilePath);
                if (!csvFile.exists() || !csvFile.isFile()) {
                    throw new BadRequestException("CSV file not found: " + req.csvFilePath);
                }
                inputStream = new FileInputStream(csvFile);
            }

            CSVImportHelper.ImportResult<UnversionedBaseModel> result = csvImportHelper.analyzeCSV(
                    repo, inputStream, fieldSep, quoteChar, true, req.columns, charset, false, "QUOTE_WHERE_ESSENTIAL");

            ImportAnalyzeResponse response = new ImportAnalyzeResponse();
            response.sessionId = result.getSessionId();
            response.rootType = root.getName();
            response.totalRows = result.getTotalRows();
            response.validRows = result.getValidRows();
            response.errorRows = result.getErrorRows();
            response.insertCount = result.getInsertCount();
            response.updateCount = result.getUpdateCount();

            // Build preview from row results (up to 100)
            response.previewRows = new ArrayList<>();
            List<CSVImportHelper.ImportRowResult<UnversionedBaseModel>> rowResults = result.getRowResults();
            int previewLimit = Math.min(rowResults.size(), 100);
            for (int i = 0; i < previewLimit; i++) {
                CSVImportHelper.ImportRowResult<UnversionedBaseModel> rr = rowResults.get(i);
                ImportRowPreview preview = new ImportRowPreview();
                preview.rowNumber = rr.getRowNumber();
                preview.refName = rr.getRefName();
                preview.intent = rr.getIntent() != null ? rr.getIntent().name() : null;
                preview.hasErrors = rr.hasErrors();
                if (rr.getErrors() != null && !rr.getErrors().isEmpty()) {
                    preview.errors = new ArrayList<>();
                    for (CSVImportHelper.FieldError fe : rr.getErrors()) {
                        ImportFieldError ife = new ImportFieldError();
                        ife.field = fe.getField();
                        ife.message = fe.getMessage();
                        ife.code = fe.getCode() != null ? fe.getCode().name() : null;
                        preview.errors.add(ife);
                    }
                }
                response.previewRows.add(preview);
            }

            return Response.ok(response).build();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf(e, "Failed to analyze CSV import for type %s", req.rootType);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ImportAnalyzeFailed");
            error.put("message", "Failed to analyze CSV: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    /**
     * Fetch analyzed CSV rows for an import session with pagination.
     * Useful for reviewing errors or large imports before committing.
     */
    @POST
    @Path("/import/rows")
    @FunctionalAction("importRows")
    public Response importRows(ImportRowsRequest req) {
        if (req.sessionId == null || req.sessionId.isBlank()) {
            throw new BadRequestException("sessionId is required");
        }

        try {
            int skip = (req.skip != null) ? req.skip : 0;
            int limit = (req.limit != null) ? req.limit : 50;
            String realm = resolveRealm(req.realm);

            // Build filter query for ImportSessionRow
            StringBuilder filter = new StringBuilder("sessionRefName:" + req.sessionId);
            if (req.onlyErrors != null && req.onlyErrors) {
                filter.append(" && hasErrors:true");
            }
            if (req.intent != null && !req.intent.isBlank()) {
                filter.append(" && intent:").append(req.intent);
            }

            List<ImportSessionRow> rows = importSessionRowRepo.getListByQuery(
                    realm, skip, limit, filter.toString(), null, null);

            ImportRowsResponse response = new ImportRowsResponse();
            response.sessionId = req.sessionId;
            response.rows = new ArrayList<>();
            for (ImportSessionRow row : rows) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put("rowNumber", row.getRowNumber());
                rowMap.put("intent", row.getIntent());
                rowMap.put("hasErrors", row.isHasErrors());
                rowMap.put("rawLine", row.getRawLine());
                if (row.getErrorsJson() != null) {
                    rowMap.put("errors", objectMapper.readValue(row.getErrorsJson(), List.class));
                }
                if (row.getRecordJson() != null) {
                    rowMap.put("record", objectMapper.readValue(row.getRecordJson(), Map.class));
                }
                response.rows.add(rowMap);
            }

            return Response.ok(response).build();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf(e, "Failed to fetch import session rows for session %s", req.sessionId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ImportRowsFailed");
            error.put("message", "Failed to fetch import rows: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    /**
     * Commit a previously analyzed CSV import session. Saves all valid (error-free)
     * INSERT and UPDATE rows to the database.
     */
    @POST
    @Path("/import/commit")
    @FunctionalAction("importCommit")
    public Response importCommit(ImportCommitRequest req) {
        if (req.sessionId == null || req.sessionId.isBlank()) {
            throw new BadRequestException("sessionId is required");
        }

        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        @SuppressWarnings("unchecked")
        BaseMorphiaRepo<UnversionedBaseModel> repo = resolveRepo((Class<UnversionedBaseModel>) root);

        try {
            CSVImportHelper.CommitResult result = csvImportHelper.commitImport(req.sessionId, repo);

            ImportCommitResponse response = new ImportCommitResponse();
            response.sessionId = req.sessionId;
            response.imported = result.getImported();
            response.failed = result.getFailed();
            return Response.ok(response).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to commit import session %s for type %s", req.sessionId, req.rootType);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ImportCommitFailed");
            error.put("message", "Failed to commit import: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    /**
     * Cancel a CSV import session, discarding all analyzed rows and session data.
     */
    @POST
    @Path("/import/cancel")
    @FunctionalAction("importCancel")
    public Response importCancel(ImportCancelRequest req) {
        if (req.sessionId == null || req.sessionId.isBlank()) {
            throw new BadRequestException("sessionId is required");
        }

        try {
            csvImportHelper.cancelImport(req.sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", req.sessionId);
            response.put("status", "cancelled");
            return Response.ok(response).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to cancel import session %s", req.sessionId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ImportCancelFailed");
            error.put("message", "Failed to cancel import: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    /**
     * Export entities matching a query as CSV. Returns CSV inline for small results
     * or writes to a temp file for large results (controlled by
     * {@code quantum.queryGateway.export.inlineThreshold}).
     */
    @POST
    @Path("/export")
    @FunctionalAction("export")
    public Response export(ExportRequest req) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(req.rootType);
        @SuppressWarnings("unchecked")
        BaseMorphiaRepo<UnversionedBaseModel> repo = resolveRepo((Class<UnversionedBaseModel>) root);

        char fieldSep = (req.fieldSeparator != null && !req.fieldSeparator.isEmpty()) ? req.fieldSeparator.charAt(0) : ',';
        char quoteChar = (req.quoteChar != null && !req.quoteChar.isEmpty()) ? req.quoteChar.charAt(0) : '"';
        Charset charset = (req.charset != null && !req.charset.isBlank()) ? Charset.forName(req.charset) : StandardCharsets.UTF_8;
        boolean includeHeader = (req.includeHeader == null || req.includeHeader);
        int limit = (req.limit != null && req.limit > 0) ? req.limit : 0; // 0 = all
        int skip = (req.skip != null) ? req.skip : 0;
        String query = req.query;

        try {
            CSVExportHelper exportHelper = new CSVExportHelper();
            StreamingOutput streamingOutput = exportHelper.streamCSVOut(
                    repo, fieldSep, req.columns, "QUOTE_WHERE_ESSENTIAL",
                    quoteChar, charset, false, query, skip, limit,
                    includeHeader, null);

            // Determine row count for threshold decision
            String realm = resolveRealm(req.realm);
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            Query<? extends UnversionedBaseModel> countQuery = ds.find(root);
            if (query != null && !query.isBlank()) {
                Map<String, String> variableMap = variableMapForQuery(req.realm);
                PlannedQuery planned = MorphiaUtils.convertToPlannedQuery(query, root, null, null, null, variableMap);
                if (planned.getFilter() != null) {
                    countQuery = countQuery.filter(planned.getFilter());
                }
            }
            long rowCount = countQuery.count();

            ExportResponse response = new ExportResponse();
            response.rootType = root.getName();
            response.rowCount = (int) Math.min(rowCount, limit > 0 ? limit : rowCount);

            if (rowCount <= exportInlineThreshold) {
                // Inline mode: capture CSV to string
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                streamingOutput.write(baos);
                response.csvContent = baos.toString(charset.name());
                response.mode = "inline";
            } else {
                // File mode: write to temp file
                File tempFile = File.createTempFile("export-", ".csv");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    streamingOutput.write(fos);
                }
                response.csvFilePath = tempFile.getAbsolutePath();
                response.mode = "file";
            }

            return Response.ok(response).build();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf(e, "Failed to export entities of type %s", req.rootType);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ExportFailed");
            error.put("message", "Failed to export: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
        }
    }

    private String resolveCollectionName(Class<? extends UnversionedBaseModel> root) {
        dev.morphia.annotations.Entity e = root.getAnnotation(dev.morphia.annotations.Entity.class);
        if (e != null && e.value() != null && !e.value().isBlank()) {
            return e.value();
        }
        return root.getSimpleName();
    }

    // The functional area for this resource (from @FunctionalMapping)
    private static final String FUNCTIONAL_AREA = "integration";

    /**
     * Resolves the realm to use for queries.
     *
     * <p>Priority order:</p>
     * <ol>
     *   <li>Explicit realm from request (if non-null and non-blank)</li>
     *   <li>Area-specific realm override from principal's area2RealmOverrides (if configured)</li>
     *   <li>SecurityContext principal's default realm (from logged-in user, includes X-Realm override)</li>
     *   <li>Configured default realm (quantum.realm.testRealm property)</li>
     * </ol>
     *
     * @param requestRealm the realm from the request (may be null or blank)
     * @return the resolved realm to use
     */
    /**
     * Builds a variable map for query planning so ontology predicates (hasEdge, hasOutgoingEdge, hasIncomingEdge)
     * receive tenant/realm context. Uses SecurityContext when available; otherwise a minimal map from the resolved realm.
     *
     * @param requestRealm optional realm from the request (may be null)
     * @return map of variable names to values (never null)
     */
    private Map<String, String> variableMapForQuery(String requestRealm) {
        java.util.Optional<PrincipalContext> pc = SecurityContext.getPrincipalContext();
        java.util.Optional<ResourceContext> rc = SecurityContext.getResourceContext();
        if (pc.isPresent() && rc.isPresent()) {
            return MorphiaUtils.createStandardVariableMapFrom(pc.get(), rc.get());
        }
        // Fallback: minimal map so ontology predicates get tenant/realm
        String realm = resolveRealm(requestRealm);
        Map<String, String> minimal = new HashMap<>();
        minimal.put("pTenantId", realm);
        minimal.put("tenantId", realm);
        minimal.put("systemTenantId", realm);
        return minimal;
    }

    private String resolveRealm(String requestRealm) {
        // 1. Explicit realm from request takes highest priority
        if (requestRealm != null && !requestRealm.isBlank()) {
            return requestRealm;
        }

        // 2. Try to get from SecurityContext
        return SecurityContext.getPrincipalContext()
                .map(pc -> {
                    // 2a. Check for area-specific realm override
                    if (pc.getArea2RealmOverrides() != null) {
                        String areaOverride = pc.getArea2RealmOverrides().get(FUNCTIONAL_AREA);
                        if (areaOverride != null && !areaOverride.isBlank()) {
                            Log.debugf("Using area2RealmOverride for area '%s': %s", FUNCTIONAL_AREA, areaOverride);
                            return areaOverride;
                        }
                    }
                    // 2b. Use principal's default realm (includes X-Realm override if applied)
                    return pc.getDefaultRealm();
                })
                .orElse(defaultRealm);
    }

    /**
     * Resolves a rootType string to its corresponding entity class.
     *
     * <p>Supports two formats:</p>
     * <ul>
     *   <li>Fully qualified class name (e.g., "com.example.models.Location")</li>
     *   <li>Simple class name (e.g., "Location") - resolved from mapped entities if unique</li>
     * </ul>
     *
     * <p>When using simple names, the method searches all mapped Morphia entities for a match.
     * If multiple entities have the same simple name, a BadRequestException is thrown with
     * the list of matching fully qualified names.</p>
     *
     * @param rootType the class name (simple or fully qualified)
     * @return the resolved entity class
     * @throws BadRequestException if rootType is null/blank, not found, ambiguous, or not a valid entity
     */
    @SuppressWarnings("unchecked")
    private Class<? extends UnversionedBaseModel> resolveRoot(String rootType) {
        if (rootType == null || rootType.isBlank()) {
            throw new BadRequestException("rootType is required");
        }

        // First, try as a fully qualified class name
        if (rootType.contains(".")) {
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

        // Treat as simple name - search mapped entities
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(defaultRealm);
        Mapper mapper = ds.getMapper();

        List<Class<?>> matches = new ArrayList<>();
        for (EntityModel em : mapper.getMappedEntities()) {
            Class<?> type = em.getType();
            if (UnversionedBaseModel.class.isAssignableFrom(type)
                    && type.getSimpleName().equals(rootType)) {
                matches.add(type);
            }
        }

        if (matches.isEmpty()) {
            throw new BadRequestException("Unknown rootType: '" + rootType + "'. " +
                "Use GET /api/query/rootTypes to see available types.");
        }

        if (matches.size() > 1) {
            List<String> fqns = matches.stream()
                .map(Class::getName)
                .sorted()
                .toList();
            throw new BadRequestException("Ambiguous rootType: '" + rootType + "' matches multiple classes: " +
                fqns + ". Please use the fully qualified class name.");
        }

        return (Class<? extends UnversionedBaseModel>) matches.get(0);
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

    // Count DTOs
    @RegisterForReflection
    public static class CountRequest {
        /** The entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional BIAPI query string to filter which entities to count. If omitted, counts all. */
        public String query;
        /** Optional realm; if absent default realm is used */
        public String realm;
    }

    @RegisterForReflection
    public static class CountResponse {
        /** The number of matching entities */
        public long count;
        /** The fully qualified class name */
        public String rootType;
        /** The query that was used (null if counting all) */
        public String query;
    }

    @RegisterForReflection
    public static class RootTypesResponse {
        public List<RootTypeInfo> rootTypes;
        public int count;
    }
    @RegisterForReflection
    public static class RootTypeInfo {
        /** Fully qualified class name - use this value for rootType parameter */
        public String className;
        /** Simple class name for display */
        public String simpleName;
        /** MongoDB collection name */
        public String collectionName;
    }

    // Save DTOs
    @RegisterForReflection
    public static class SaveRequest {
        /** The entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional realm; if absent default realm is used */
        public String realm;
        /** The entity data as a Map (will be converted to the target type) */
        public Map<String, Object> entity;
    }

    @RegisterForReflection
    public static class SaveResponse {
        /** The ID of the saved entity */
        public String id;
        /** The fully qualified class name */
        public String rootType;
        /** The saved entity data */
        @SuppressWarnings("rawtypes")
        public Map entity;
    }

    // Delete DTOs
    @RegisterForReflection
    public static class DeleteRequest {
        /** The entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional realm; if absent default realm is used */
        public String realm;
        /** The ObjectId of the entity to delete (hex string) */
        public String id;
    }

    @RegisterForReflection
    public static class DeleteResponse {
        /** Whether the delete was successful */
        public boolean success;
        /** The ID that was deleted */
        public String id;
        /** The fully qualified class name */
        public String rootType;
        /** Number of documents deleted (0 or 1) */
        public long deletedCount;
    }

    @RegisterForReflection
    public static class DeleteManyRequest {
        /** The entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional realm; if absent default realm is used */
        public String realm;
        /** Query to match entities to delete (same syntax as /find) */
        public String query;
    }

    @RegisterForReflection
    public static class DeleteManyResponse {
        /** Whether the operation completed successfully */
        public boolean success;
        /** The query that was used */
        public String query;
        /** The fully qualified class name */
        public String rootType;
        /** Number of documents deleted */
        public long deletedCount;
    }

    // ========================================================================
    // IMPORT DTOs
    // ========================================================================

    @RegisterForReflection
    public static class ImportAnalyzeRequest {
        /** Entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional realm; if absent default realm is used */
        public String realm;
        /** CSV content as a string (mutually exclusive with csvFilePath) */
        public String csvContent;
        /** Server-side file path to a CSV file (mutually exclusive with csvContent) */
        public String csvFilePath;
        /** Ordered list of entity field names matching CSV columns */
        public List<String> columns;
        /** Field separator character (default ',') */
        public String fieldSeparator;
        /** Quote character (default '"') */
        public String quoteChar;
        /** Character set (default UTF-8) */
        public String charset;
    }

    @RegisterForReflection
    public static class ImportAnalyzeResponse {
        public String sessionId;
        public String rootType;
        public int totalRows;
        public int validRows;
        public int errorRows;
        public int insertCount;
        public int updateCount;
        public List<ImportRowPreview> previewRows;
    }

    @RegisterForReflection
    public static class ImportRowPreview {
        public int rowNumber;
        public String refName;
        public String intent;
        public boolean hasErrors;
        public List<ImportFieldError> errors;
    }

    @RegisterForReflection
    public static class ImportFieldError {
        public String field;
        public String message;
        public String code;
    }

    @RegisterForReflection
    public static class ImportRowsRequest {
        public String sessionId;
        public String rootType;
        public String realm;
        public Integer skip;
        public Integer limit;
        public Boolean onlyErrors;
        public String intent;
    }

    @RegisterForReflection
    public static class ImportRowsResponse {
        public String sessionId;
        public List<Map<String, Object>> rows;
    }

    @RegisterForReflection
    public static class ImportCommitRequest {
        public String sessionId;
        public String rootType;
        public String realm;
    }

    @RegisterForReflection
    public static class ImportCommitResponse {
        public String sessionId;
        public int imported;
        public int failed;
    }

    @RegisterForReflection
    public static class ImportCancelRequest {
        public String sessionId;
        public String rootType;
        public String realm;
    }

    // ========================================================================
    // EXPORT DTOs
    // ========================================================================

    @RegisterForReflection
    public static class ExportRequest {
        /** Entity type (simple or fully qualified class name) */
        public String rootType;
        /** Optional realm; if absent default realm is used */
        public String realm;
        /** Optional BIAPI filter query */
        public String query;
        /** Ordered list of columns/projections to include in export */
        public List<String> columns;
        /** Max rows to export (default: all) */
        public Integer limit;
        /** Rows to skip (default 0) */
        public Integer skip;
        /** Field separator character (default ',') */
        public String fieldSeparator;
        /** Quote character (default '"') */
        public String quoteChar;
        /** Character set (default UTF-8) */
        public String charset;
        /** Whether to include a header row (default true) */
        public Boolean includeHeader;
    }

    @RegisterForReflection
    public static class ExportResponse {
        /** Inline CSV content (when mode is "inline") */
        public String csvContent;
        /** File path where CSV was written (when mode is "file") */
        public String csvFilePath;
        /** Total rows exported */
        public int rowCount;
        /** The fully qualified class name */
        public String rootType;
        /** "inline" or "file" */
        public String mode;
    }
}
