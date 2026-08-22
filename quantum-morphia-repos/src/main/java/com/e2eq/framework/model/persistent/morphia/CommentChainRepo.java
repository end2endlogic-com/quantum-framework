package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.collaboration.CommentChain;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.bson.types.ObjectId;

@ApplicationScoped
public class CommentChainRepo extends MorphiaRepo<CommentChain> {

    public List<CommentChain> findByQuantumSubject(ObjectId entityId) {
        return findGoverned(Filters.eq("subject.quantumEntity.entityId", entityId));
    }

    public List<CommentChain> findByExternalSubject(
            String sourceSystem,
            String entityType,
            String externalId) {
        return findGoverned(
                Filters.eq("subject.externalEntity.sourceSystem", sourceSystem),
                Filters.eq("subject.externalEntity.entityType", entityType),
                Filters.eq("subject.externalEntity.externalId", externalId));
    }

    private List<CommentChain> findGoverned(Filter... requestedFilters) {
        List<Filter> filters = new ArrayList<>(List.of(requestedFilters));
        Filter[] governed = getFilterArray(filters, getPersistentClass());
        return getMorphiaDataStore()
                .find(CommentChain.class)
                .filter(governed)
                .iterator()
                .toList();
    }
}
