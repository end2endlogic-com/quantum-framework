package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.mongo.AnnotatedEdgeExtractor;
import com.e2eq.ontology.mongo.OntologyMaterializer;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String status = "IDLE";

    public boolean isRunning() { return running.get(); }
    public String status() { return status; }

    public void runAsync(String realmId) {
        runAsync(realmId, false);
    }

    public void runAsync(String realmId, boolean force) {
        if (!running.compareAndSet(false, true)) {
            Log.info("OntologyReindexer: already running; ignoring new request");
            return;
        }
        status = "RUNNING";
        new Thread(() -> {
            try {
                runInternal(realmId, force);
                // After successful reindex, mark the observed YAML hash as applied
                Optional<String> src = metaService.getMeta().map(m -> m.getSource());
                Optional<Path> p = src.filter(s -> s != null && !s.equals("<none>")).map(Path::of).filter(Files::exists);
                var res = metaService.observeYaml(p, "/ontology.yaml");
                // Get tboxHash from runtime registry and yamlVersion from metadata
                String tboxHash = registryProvider.getRegistryForRealm(realmId).getTBoxHash();
                Integer yamlVersion = res.meta().getYamlVersion();
                metaService.markApplied(res.currentHash(), tboxHash, yamlVersion);
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
        // Discover ontology participant classes from Morphia mapper
        var datastore = morphiaDataStoreWrapper.getDataStore(realmId);
        Collection<Class<?>> entityClasses = discoverEntityClasses(datastore.getMapper());
        List<Class<?>> participants = new ArrayList<>();
        for (Class<?> c : entityClasses) if (c.isAnnotationPresent(OntologyClass.class)) participants.add(c);
        Log.infof("OntologyReindexer: found %d ontology participant classes", participants.size());

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
                            edgeRepo.deleteDerivedByDataDomain(dataDomain);
                        } catch (Throwable t) {
                            Log.warn("Failed to purge derived edges for DataDomain " + dataDomain.getTenantId() + ": " + t.getMessage());
                        }
                    }

                    List<Reasoner.Edge> explicit = extractor.fromEntity(realmId, entity);
                    materializer.apply(dataDomain, srcId, entityType, explicit);
                    processed++;
                    if (processed % 100 == 0) {
                        status = String.format(Locale.ROOT, "Materialized %d %s", processed, clazz.getSimpleName());
                    }
                } catch (Throwable t) {
                    Log.warnf("OntologyReindexer: failed to materialize for %s due to %s", clazz.getSimpleName(), t.getMessage());
                }
            }
            Log.infof("OntologyReindexer: completed %s", clazz.getSimpleName());
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
