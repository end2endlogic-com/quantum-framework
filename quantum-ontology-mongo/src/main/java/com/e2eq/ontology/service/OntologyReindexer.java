package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.mongo.AnnotatedEdgeExtractor;
import com.e2eq.ontology.mongo.DataDomainConverter;
import com.e2eq.ontology.mongo.OntologyMaterializer;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background/full reindex service that recomputes ontology edges for all participating entities.
 */
@ApplicationScoped
public class OntologyReindexer {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    AnnotatedEdgeExtractor extractor;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    com.e2eq.ontology.repo.OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyMetaService metaService;

    @Inject
    com.e2eq.ontology.runtime.TenantOntologyRegistryProvider registryProvider;

    @Inject
    Instance<OntologyEdgeProvider> providers;

    /**
     * Summary of edge changes grouped by predicate and origin.
     */
    public record EdgeChangeSummary(
            int totalAdded,
            int totalModified,
            int totalRemoved,
            Map<String, Integer> addedByPredicate,
            Map<String, Integer> removedByPredicate,
            Map<String, Integer> addedByOrigin,  // explicit, inferred, computed
            Map<String, Integer> removedByOrigin
    ) {
        public static EdgeChangeSummary from(com.e2eq.ontology.core.EdgeChanges changes) {
            Map<String, Integer> addedByP = new HashMap<>();
            Map<String, Integer> removedByP = new HashMap<>();
            Map<String, Integer> addedByO = new HashMap<>();
            Map<String, Integer> removedByO = new HashMap<>();

            for (var e : changes.added()) {
                addedByP.merge(e.getP(), 1, Integer::sum);
                addedByO.merge(classifyOrigin(e), 1, Integer::sum);
            }
            for (var e : changes.removed()) {
                removedByP.merge(e.getP(), 1, Integer::sum);
                removedByO.merge(classifyOrigin(e), 1, Integer::sum);
            }

            return new EdgeChangeSummary(
                    changes.added().size(),
                    changes.modified().size(),
                    changes.removed().size(),
                    addedByP,
                    removedByP,
                    addedByO,
                    removedByO
            );
        }

        private static String classifyOrigin(com.e2eq.ontology.core.EdgeRecord e) {
            if (e.isDerived() && !e.isInferred()) return "computed";
            if (e.isInferred()) return "inferred";
            return "explicit";
        }
    }

    /**
     * Enhanced reindex result with summary statistics.
     */
    public record ReindexResult(
            String status,
            EdgeChanges changes,
            EdgeChangeSummary summary,
            int entitiesProcessed,
            List<String> participatingClasses
    ) {
        // Legacy constructor for backward compatibility
        public ReindexResult(String status, EdgeChanges changes) {
            this(status, changes, EdgeChangeSummary.from(changes), 0, List.of());
        }
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String status = "IDLE";
    private volatile EdgeChanges lastChanges = EdgeChanges.empty();
    private volatile int entitiesProcessed = 0;
    private volatile List<String> participatingClasses = new ArrayList<>();

    public boolean isRunning() { return running.get(); }
    public String status() { return status; }
    public EdgeChanges lastChanges() { return lastChanges; }

    public ReindexResult getResult() {
        return new ReindexResult(
                status,
                lastChanges,
                EdgeChangeSummary.from(lastChanges),
                entitiesProcessed,
                participatingClasses
        );
    }

    public void runAsync(String realmId) {
        runAsync(realmId, false);
    }

    public void runAsync(String realmId, boolean force) {
        if (!running.compareAndSet(false, true)) {
            Log.info("OntologyReindexer: already running; ignoring new request");
            return;
        }
        status = "RUNNING";
        lastChanges = EdgeChanges.empty();
        entitiesProcessed = 0;
        participatingClasses = new ArrayList<>();
        new Thread(() -> {
            try {
                DataDomain dd = new DataDomain();
                dd.setOrgRefName("ontology");
                dd.setAccountNum("0000000000");
                dd.setTenantId(realmId);
                dd.setOwnerId("system");
                dd.setDataSegment(0);

                PrincipalContext principal = SecurityCallScope.service(realmId, dd, "ontology-reindexer", "SYSTEM");
                // Explicitly use action 'seed' to bypass PermissionRuleInterceptor
                ResourceContext resource = new ResourceContext.Builder()
                        .withRealm(realmId)
                        .withArea("*")
                        .withFunctionalDomain("*")
                        .withAction("seed")
                        .build();

                SecurityCallScope.runWithContexts(principal, resource, () -> {
                    runInternal(realmId, force);
                    // After successful reindex, mark the observed YAML hash as applied
                    Optional<String> src = metaService.getMeta(realmId).map(m -> m.getSource());
                    Optional<Path> p = src.filter(s -> s != null && !s.equals("<none>")).map(Path::of).filter(Files::exists);
                    var res = metaService.observeYaml(realmId, p, "/ontology.yaml");
                    // Get tboxHash from runtime registry and yamlVersion from metadata
                    String tboxHash = registryProvider.getRegistryForRealm(realmId).getTBoxHash();
                    Integer yamlVersion = res.meta().getYamlVersion();
                    metaService.markApplied(realmId, res.currentHash(), tboxHash, yamlVersion);
                });
                status = "COMPLETED";
            } catch (Throwable t) {
                status = "FAILED: " + t.getMessage();
                Log.error("Ontology reindex failed", t);
            } finally {
                running.set(false);
            }
        }, "ontology-reindexer").start();
    }

    private void runInternal(String realmId, boolean force) {
        // First pass: materialize explicit edges and direct inferences
        processAll(realmId, force);
        // Second pass: catch inverses and more complex inferences that depend on the first pass
        processAll(realmId, false);
    }

    private void processAll(String realmId, boolean force) {
        // Discover ontology participant classes from Morphia mapper
        var datastore = morphiaDataStoreWrapper.getDataStore(realmId);
        Collection<Class<?>> entityClasses = discoverEntityClasses(datastore.getMapper());
        List<Class<?>> participants = new ArrayList<>();
        for (Class<?> c : entityClasses) if (c.isAnnotationPresent(OntologyClass.class)) participants.add(c);
        Log.infof("OntologyReindexer: found %d ontology participant classes", participants.size());

        // Track participating classes (only add on first pass to avoid duplicates)
        if (participatingClasses.isEmpty()) {
            for (Class<?> c : participants) {
                String classId = extractor.metaOf(c).map(m -> m.classId).orElse(c.getSimpleName());
                if (!participatingClasses.contains(classId)) {
                    participatingClasses.add(classId);
                }
            }
        }

        // If force, collect all unique DataDomains we'll encounter and purge derived edges for each
        Set<DataDomain> processedDataDomains = new HashSet<>();

        // For each class, load all instances (ids only if possible) and recompute edges
        for (Class<?> clazz : participants) {
            status = "Scanning " + clazz.getSimpleName();
            var ds = morphiaDataStoreWrapper.getDataStore(realmId);
            var q = ds.find(clazz);
            int processed = 0;
            var list = q.iterator().toList();
            for (Object entity : list) {
                try {
                    String srcId = extractor.idOf(entity);
                    String entityType = extractor.metaOf(clazz).map(m -> m.classId).orElse(clazz.getSimpleName());

                    // Extract DataDomain from entity
                    DataDomain dataDomain = extractDataDomain(entity, realmId);

                    // If force mode and first time seeing this DataDomain, purge derived edges
                    if (force && processedDataDomains.add(dataDomain)) {
                        try {
                            status = "PURGE_DERIVED for " + dataDomain.getTenantId();
                            edgeRepo.deleteDerivedByDataDomain(realmId, dataDomain);
                        } catch (Throwable t) {
                            Log.warn("Failed to purge derived edges for DataDomain " + dataDomain.getTenantId() + ": " + t.getMessage());
                        }
                    }

                    List<Reasoner.Edge> explicit = new ArrayList<>(extractor.fromEntity(realmId, entity));

                    // Extend with SPI-provided edges (includes ComputedEdgeProviders)
                    DataDomainInfo dataDomainInfo = DataDomainConverter.toInfo(dataDomain);
                    try {
                        for (OntologyEdgeProvider p : providers) {
                            if (p.supports(clazz)) {
                                var extra = p.edges(realmId, dataDomainInfo, entity);
                                if (extra != null && !extra.isEmpty()) {
                                    explicit.addAll(extra);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.warn("OntologyReindexer: provider extension failed for " + clazz.getSimpleName(), t);
                    }

                    EdgeChanges entityChanges = materializer.apply(realmId, dataDomain, srcId, entityType, explicit);
                    lastChanges.addAll(entityChanges);
                    processed++;
                    entitiesProcessed++;
                    if (processed % 100 == 0) {
                        status = String.format(Locale.ROOT, "Materialized %d %s (total: %d)", processed, clazz.getSimpleName(), entitiesProcessed);
                    }
                } catch (Throwable t) {
                    Log.warnf("OntologyReindexer: failed to materialize for %s due to %s", clazz.getSimpleName(), t.getMessage());
                }
            }
            Log.infof("OntologyReindexer: completed %s (%d entities)", clazz.getSimpleName(), processed);
        }
    }

    /**
     * Extracts DataDomain from an entity, with fallback for backward compatibility.
     */
    private DataDomain extractDataDomain(Object entity, String realmId) {
        if (entity instanceof UnversionedBaseModel model) {
            DataDomain dd = model.getDataDomain();
            if (dd != null && dd.getOrgRefName() != null && dd.getAccountNum() != null && dd.getTenantId() != null) {
                return dd;
            }
        }
        // Fallback DataDomain for backward compatibility
        DataDomain dd = new DataDomain();
        dd.setOrgRefName("ontology");
        dd.setAccountNum("0000000000");
        dd.setTenantId(realmId);
        dd.setOwnerId("system");
        dd.setDataSegment(0);
        return dd;
    }

    private Collection<Class<?>> discoverEntityClasses(dev.morphia.mapping.Mapper mapper) {
        try {
            // Use getMappedEntities() which returns List<EntityModel>
            java.util.List<dev.morphia.mapping.codec.pojo.EntityModel> entityModels = mapper.getMappedEntities();
            Collection<Class<?>> classes = new LinkedHashSet<>();
            for (dev.morphia.mapping.codec.pojo.EntityModel model : entityModels) {
                try {
                    Class<?> entityClass = model.getType();
                    if (entityClass != null) {
                        classes.add(entityClass);
                    }
                } catch (Exception e) {
                    // If getType() fails, try alternative approach
                    try {
                        // Some Morphia versions may have getEntityClass() method
                        java.lang.reflect.Method getEntityClass = model.getClass().getMethod("getEntityClass");
                        Object c = getEntityClass.invoke(model);
                        if (c instanceof Class<?>) {
                            classes.add((Class<?>) c);
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Skip this model if we can't determine its type
                        Log.debugf("OntologyReindexer: unable to determine type for EntityModel: %s", model);
                    }
                }
            }
            return classes;
        } catch (Exception e) {
            Log.warn("OntologyReindexer: failed to discover entity classes", e);
            return List.of();
        }
    }
}
