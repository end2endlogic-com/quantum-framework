package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.mongo.EdgeRelationStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import dev.morphia.Datastore;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class TestOrderRepo extends MorphiaRepo<TestOrder> {

    public List<TestOrder> listByHasEdge(String realmId, String predicate, String dstId, EdgeRelationStore edgeDao) {
        // Compute ids via ontology and constrain Morphia query
        Set<String> ids = edgeDao.srcIdsByDst(getTenantIdFromRealm(realmId), predicate, dstId);
        if (ids.isEmpty()) return java.util.List.of();
        Datastore datastore = morphiaDataStore.getDataStore(realmId);
        MongoCollection<Document> col = datastore.getDatabase().getCollection("orders");
        List<TestOrder> out = new ArrayList<>();
        for (Document d : col.find(Filters.in("refName", ids))) {
            TestOrder t = new TestOrder();
            t.setRefName(d.getString("refName"));
            t.setDisplayName(d.getString("displayName"));
            t.setStatus(d.getString("status"));
            out.add(t);
        }
        return out;
    }

    private String getTenantIdFromRealm(String realmId) {
        // In tests we use realm as DB name and the tenantId stored in DataDomain.tenantId
        // For simplicity here we assume tenantId equals realmId; test will set them consistently
        return realmId;
    }
}
