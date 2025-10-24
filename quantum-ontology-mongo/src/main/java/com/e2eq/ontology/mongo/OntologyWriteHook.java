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
        var metaOpt = extractor.metaOf(entity.getClass());
        if (metaOpt.isEmpty()) return; // not an ontology participant
        String entityType = metaOpt.get().classId;
        // Use realmId as tenant id in ontology edges
        List<Reasoner.Edge> explicit = extractor.fromEntity(realmId, entity);
        if (explicit.isEmpty()) return;
        String srcId = extractor.idAccessor.idOf(entity);
        materializer.apply(realmId, srcId, entityType, explicit);
    }
}
