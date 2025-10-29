package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.morphia.PostPersistHook;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.Reasoner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Post-persist hook that auto-materializes ontology edges for annotated entities.
 */
@ApplicationScoped
public class OntologyWriteHook implements PostPersistHook {

    @Inject AnnotatedEdgeExtractor extractor;
    @Inject OntologyMaterializer materializer;

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
        List<Reasoner.Edge> explicit = extractor.fromEntity(realmId, entity);
        try {
            io.quarkus.logging.Log.infof("[DEBUG_LOG] OntologyWriteHook.afterPersist entityType=%s, realm=%s, explicitEdges=%d", entityType, realmId, explicit.size());
            for (Reasoner.Edge e : explicit) {
                io.quarkus.logging.Log.infof("[DEBUG_LOG]   explicit: (%s)-['%s']->(%s)", e.srcId(), e.p(), e.dstId());
            }
        } catch (Exception ignored) {}
        if (explicit.isEmpty()) return;
        String srcId = extractor.idOf(entity);
        materializer.apply(realmId, srcId, entityType, explicit);
    }
}
