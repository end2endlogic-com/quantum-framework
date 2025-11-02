package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.morphia.PostPersistHook;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-persist hook that auto-materializes ontology edges for annotated entities.
 */
@ApplicationScoped
public class OntologyWriteHook implements PostPersistHook {

    @Inject AnnotatedEdgeExtractor extractor;
    @Inject OntologyMaterializer materializer;
    @Inject CascadeExecutor cascadeExecutor;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject Instance<OntologyEdgeProvider> providers;

    @Override
    public void afterPersist(String realmId, Object entity) {
        try {
            Class<?> c = entity.getClass();
            var oc = c.getAnnotation(com.e2eq.ontology.annotations.OntologyClass.class);
            long propCount = java.util.Arrays.stream(c.getDeclaredFields()).filter(f -> f.getAnnotation(com.e2eq.ontology.annotations.OntologyProperty.class) != null).count();
            io.quarkus.logging.Log.infof("[DEBUG_LOG] afterPersist on %s, has @OntologyClass=%s, annotatedProps=%d", c.getName(), (oc!=null), propCount);
        } catch (Exception ignored) {}
        var metaOpt = extractor.metaOf(entity.getClass());
        if (metaOpt.isEmpty()) return; // not an ontology participant
        String entityType = metaOpt.get().classId;
        // Use realmId as tenant id in ontology edges
        List<Reasoner.Edge> explicit = new ArrayList<>(extractor.fromEntity(realmId, entity));
        // Extend with SPI-provided edges
        try {
            for (OntologyEdgeProvider p : providers) {
                if (p.supports(entity.getClass())) {
                    var extra = p.edges(realmId, entity);
                    if (extra != null && !extra.isEmpty()) explicit.addAll(extra);
                }
            }
        } catch (Throwable t) {
            io.quarkus.logging.Log.warn("[DEBUG_LOG] OntologyWriteHook: provider extension failed", t);
        }
        try {
            io.quarkus.logging.Log.infof("[DEBUG_LOG] OntologyWriteHook.afterPersist entityType=%s, realm=%s, explicitEdges=%d", entityType, realmId, explicit.size());
            for (Reasoner.Edge e : explicit) {
                io.quarkus.logging.Log.infof("[DEBUG_LOG]   explicit: (%s)-['%s']->(%s)", e.srcId(), e.p(), e.dstId());
            }
        } catch (Exception ignored) {}
        String srcId = extractor.idOf(entity);
        // Capture prior edges for this source to support cascade diffs
        java.util.List<OntologyEdge> priorAll = edgeRepo.findBySrc(realmId, srcId);
        java.util.List<OntologyEdge> priorExplicit = new java.util.ArrayList<>();
        for (OntologyEdge e : priorAll) if (!e.isInferred()) priorExplicit.add(e);
        // First, apply materialization so explicit edges are upserted and stale ones pruned
        materializer.apply(realmId, srcId, entityType, explicit);
        // Then handle ORPHAN_REMOVE cascade based on prior vs new state and repo contents
        try { cascadeExecutor.onAfterPersist(realmId, srcId, entity, priorExplicit, explicit); } catch (Throwable ignored) {}
    }
}
