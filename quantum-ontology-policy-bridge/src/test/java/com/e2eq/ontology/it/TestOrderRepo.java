package com.e2eq.ontology.it;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;

import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class TestOrderRepo extends MorphiaRepo<TestOrder> {

    public List<TestOrder> listByHasEdge(String realmId, String predicate, String dstId, OntologyEdgeRepo edgeDao) {
        // Compute ids via ontology and constrain Morphia query using Morphia Filters
        Set<String> ids = edgeDao.srcIdsByDst(realmId, predicate, dstId);
        if (ids.isEmpty()) return java.util.List.of();
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(realmId);
        // Use raw collection to avoid mapping issues in tests
        var coll = datastore.getDatabase().getCollection("orders");
        List<TestOrder> out = new ArrayList<>();
        for (Document d : coll.find(com.mongodb.client.model.Filters.in("refName", ids))) {
            TestOrder t = new TestOrder();
            t.setRefName(d.getString("refName"));
            Object status = d.get("status");
            if (status != null) t.setStatus(status.toString());
            out.add(t);
        }
        return out;
    }

}
