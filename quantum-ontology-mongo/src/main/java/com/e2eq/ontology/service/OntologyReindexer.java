package com.e2eq.ontology.service;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
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
    MorphiaDataStore morphiaDataStore;

    @Inject
    AnnotatedEdgeExtractor extractor;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    com.e2eq.ontology.repo.OntologyEdgeRepo edgeRepo;

    @Inject
    OntologyMetaService metaService;

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
                metaService.markApplied(res.currentHash());
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
        // Optionally purge existing derived edges before recomputation
        if (force) {
            try {
                status = "PURGE_DERIVED";
                edgeRepo.deleteDerivedByTenant(realmId);
            } catch (Throwable t) {
                Log.warn("Failed to purge derived edges before reindex: " + t.getMessage());
            }
        }
        // Discover ontology participant classes from Morphia mapper
        var datastore = morphiaDataStore.getDataStore(realmId);
        Collection<Class<?>> entityClasses = discoverEntityClasses(datastore.getMapper());
        List<Class<?>> participants = new ArrayList<>();
        for (Class<?> c : entityClasses) if (c.isAnnotationPresent(OntologyClass.class)) participants.add(c);
        Log.infof("OntologyReindexer: found %d ontology participant classes", participants.size());

        // For each class, load all instances (ids only if possible) and recompute edges
        for (Class<?> clazz : participants) {
            status = "Scanning " + clazz.getSimpleName();
            var ds = morphiaDataStore.getDataStore(realmId);
            var q = ds.find(clazz);
            int processed = 0;
            var list = q.iterator().toList();
            for (Object entity : list) {
                try {
                    String srcId = extractor.idOf(entity);
                    String entityType = extractor.metaOf(clazz).map(m -> m.classId).orElse(clazz.getSimpleName());
                    List<Reasoner.Edge> explicit = extractor.fromEntity(realmId, entity);
                    materializer.apply(realmId, srcId, entityType, explicit);
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

    @SuppressWarnings("unchecked")
    private Collection<Class<?>> discoverEntityClasses(dev.morphia.mapping.Mapper mapper) {
        try {
            var method = mapper.getClass().getMethod("getEntityModels");
            Object models = method.invoke(mapper);
            Collection<Class<?>> classes = new LinkedHashSet<>();
            for (Object m : (Collection<?>) models) {
                try {
                    var getType = m.getClass().getMethod("getType");
                    Object c = getType.invoke(m);
                    if (c instanceof Class<?>) classes.add((Class<?>) c);
                } catch (NoSuchMethodException ignored) {
                    try {
                        var getEntityClass = m.getClass().getMethod("getEntityClass");
                        Object c = getEntityClass.invoke(m);
                        if (c instanceof Class<?>) classes.add((Class<?>) c);
                    } catch (NoSuchMethodException ignored2) { }
                }
            }
            return classes;
        } catch (Exception e) {
            Log.warn("OntologyReindexer: failed to discover entity classes", e);
            return List.of();
        }
    }
}
