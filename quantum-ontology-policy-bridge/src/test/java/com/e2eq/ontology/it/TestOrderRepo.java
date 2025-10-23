package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.mongo.EdgeRelationStore;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class TestOrderRepo extends MorphiaRepo<TestOrder> {

    public List<TestOrder> listByHasEdge(String realmId, String predicate, String dstId, EdgeRelationStore edgeDao) {
        // Compute ids via ontology and constrain Morphia query using Morphia Filters
        Set<String> ids = edgeDao.srcIdsByDst(getTenantIdFromRealm(realmId), predicate, dstId);
        if (ids.isEmpty()) return java.util.List.of();
        Datastore datastore = morphiaDataStore.getDataStore(realmId);
        Query<TestOrder> q = datastore.find(TestOrder.class)
                .filter(Filters.in("refName", ids));
        return q.iterator().toList();
    }

    private String getTenantIdFromRealm(String realmId) {
        // In tests we use realm as DB name and the tenantId stored in DataDomain.tenantId
        // For simplicity here we assume tenantId equals realmId; test will set them consistently
        return realmId;
    }
}
