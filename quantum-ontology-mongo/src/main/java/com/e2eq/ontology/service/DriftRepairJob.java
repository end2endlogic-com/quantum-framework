package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.mongo.AnnotatedEdgeExtractor;
import com.e2eq.ontology.mongo.DataDomainConverter;
import com.e2eq.ontology.mongo.OntologyMaterializer;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Drift repair job that:
 * - Prunes derived edges without support (safety: can be run independently).
 * - Re-derives missing edges by scanning current base graph for a given entity class, page by page.
 * All operations are scoped by DataDomain for proper isolation.
 * Supports: per-realm, per-entity class, pagination via page size + _id cursor token.
 */
@ApplicationScoped
public class DriftRepairJob {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;
    @Inject
    AnnotatedEdgeExtractor extractor;
    @Inject
    OntologyMaterializer materializer;
    @Inject
    OntologyEdgeRepo edgeRepo;
    @Inject
    jakarta.enterprise.inject.Instance<OntologyEdgeProvider> providers;

    public static final class Request {
        public String realmId;
        public String entityClass; // FQN of Morphia entity
        public Integer pageSize; // default 100
        public String pageToken; // last _id processed (hex string)
        public boolean prune = true;
        public boolean derive = true;
        public boolean dryRun = false;
    }

    public static final class Result {
        public String realmId;
        public String entityClass;
        public int scanned;
        public int derivedApplied;
        public boolean pruned;
        public String nextPageToken; // last _id from this page
        public List<String> warnings = new ArrayList<>();
    }

    public Result run(Request req) {
        Objects.requireNonNull(req, "request");
        if (req.realmId == null || req.realmId.isBlank()) throw new IllegalArgumentException("realmId is required");
        if (req.entityClass == null || req.entityClass.isBlank()) throw new IllegalArgumentException("entityClass is required (FQN)");
        int pageSize = Optional.ofNullable(req.pageSize).filter(ps -> ps > 0 && ps <= 10_000).orElse(100);

        Class<?> clazz;
        try {
            clazz = Class.forName(req.entityClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Entity class not found: " + req.entityClass);
        }
        if (!UnversionedBaseModel.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Entity class must extend UnversionedBaseModel: " + req.entityClass);
        }
        if (!clazz.isAnnotationPresent(OntologyClass.class)) {
            Log.warnf("DriftRepair: class %s is not annotated with @OntologyClass; continuing anyway", clazz.getName());
        }

        Result res = new Result();
        res.realmId = req.realmId;
        res.entityClass = clazz.getName();

        // Phase 2: re-derive edges for this entity class page-by-page
        Datastore ds = morphiaDataStoreWrapper.getDataStore(req.realmId);
        @SuppressWarnings("unchecked")
        Class<? extends UnversionedBaseModel> modelClass = (Class<? extends UnversionedBaseModel>) clazz;
        Query<? extends UnversionedBaseModel> q = ds.find(modelClass);
        if (req.pageToken != null && !req.pageToken.isBlank()) {
            try {
                org.bson.types.ObjectId lastId = new org.bson.types.ObjectId(req.pageToken);
                q = q.filter(Filters.gt("_id", lastId));
            } catch (IllegalArgumentException bad) {
                String msg = "Invalid pageToken (must be Mongo ObjectId hex): " + req.pageToken;
                res.warnings.add(msg);
            }
        }
        List<? extends UnversionedBaseModel> page = q.iterator(new FindOptions().limit(pageSize).sort(dev.morphia.query.Sort.ascending("_id"))).toList();

        // Phase 1: prune derived without support (per DataDomain encountered)
        Set<String> prunedDomains = new HashSet<>();
        
        String lastIdHex = null;
        int derivedApplied = 0;
        for (UnversionedBaseModel entity : page) {
            res.scanned++;
            try {
                // Extract DataDomain from entity
                DataDomain dataDomain = entity.getDataDomain();
                if (dataDomain == null || dataDomain.getOrgRefName() == null || 
                    dataDomain.getAccountNum() == null || dataDomain.getTenantId() == null) {
                    // Create fallback DataDomain for entities without proper scoping
                    dataDomain = createFallbackDataDomain(req.realmId);
                    Log.warnf("DriftRepair: Entity %s missing DataDomain, using fallback", entity.getClass().getSimpleName());
                }
                
                // Prune derived edges for this DataDomain (once per unique domain)
                DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
                if (req.prune) {
                    String domainKey = dataDomain.getOrgRefName() + "/" + dataDomain.getAccountNum() + "/" + 
                                       dataDomain.getTenantId() + "/" + dataDomain.getDataSegment();
                    if (prunedDomains.add(domainKey)) {
                        try {
                            if (!req.dryRun) edgeRepo.pruneDerivedWithoutSupport(dataDomain);
                            res.pruned = true;
                        } catch (Throwable t) {
                            String msg = "Prune failed for domain " + domainKey + ": " + t.getMessage();
                            res.warnings.add(msg);
                            Log.warn(msg, t);
                        }
                    }
                }
                
                if (!req.derive) continue;
                
                String srcId = extractor.idOf(entity);
                String entityType = extractor.metaOf(clazz).map(m -> m.classId).orElse(clazz.getSimpleName());
                // Explicit edges come from annotations + providers
                List<Reasoner.Edge> explicit = new ArrayList<>(extractor.fromEntity(req.realmId, entity));
                for (OntologyEdgeProvider p : providers) {
                    try {
                        if (p.supports(clazz)) {
                            List<Reasoner.Edge> extra = p.edges(req.realmId, dataDomainInfo, entity);
                            if (extra != null) explicit.addAll(extra);
                        }
                    } catch (Throwable t) {
                        Log.warnf("DriftRepair: provider %s failed for %s: %s", p.getClass().getSimpleName(), clazz.getSimpleName(), t.getMessage());
                    }
                }
                if (!req.dryRun) {
                    materializer.apply(req.realmId, dataDomain, srcId, entityType, explicit);
                    derivedApplied++; // approximate counter; materializer handles diffing
                }
                // track cursor
                try {
                    var idField = entity.getClass().getDeclaredField("id");
                    idField.setAccessible(true);
                    Object oid = idField.get(entity);
                    if (oid instanceof org.bson.types.ObjectId) lastIdHex = ((org.bson.types.ObjectId) oid).toHexString();
                } catch (Exception ignored) { }
            } catch (Throwable t) {
                String msg = String.format(Locale.ROOT, "Materialize failed for %s: %s", clazz.getSimpleName(), t.getMessage());
                res.warnings.add(msg);
                Log.warn(msg, t);
            }
        }
        res.derivedApplied = derivedApplied;
        res.nextPageToken = lastIdHex; // if null, caller can treat as done
        return res;
    }
    
    /**
     * Creates a fallback DataDomain for entities without proper scoping.
     */
    private DataDomain createFallbackDataDomain(String realmId) {
        DataDomain dd = new DataDomain();
        dd.setOrgRefName("ontology");
        dd.setAccountNum("0000000000");
        dd.setTenantId(realmId);
        dd.setOwnerId("system");
        dd.setDataSegment(0);
        return dd;
    }
}
