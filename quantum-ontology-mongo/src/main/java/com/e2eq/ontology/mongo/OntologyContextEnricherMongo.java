
package com.e2eq.ontology.mongo;

import java.util.*;
import org.bson.Document;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OntologyContextEnricherMongo {

    @jakarta.inject.Inject
    private EdgeRelationStore edgeDao;

    // No-arg constructor for CDI
    public OntologyContextEnricherMongo() {}

    @SuppressWarnings("unchecked")
    public Map<String,Object> enrich(String tenantId, String resourceId){
        Map<String,Object> rc = new HashMap<>();
        var edges = edgeDao.findBySrc(tenantId, resourceId);
        List<Map<String,Object>> edgeSummaries = new ArrayList<>();
        for (Object o : edges){
            if (o instanceof org.bson.Document d) {
                edgeSummaries.add(Map.of(
                        "p", d.getString("p"),
                        "dst", d.getString("dst"),
                        "inferred", d.getBoolean("inferred", Boolean.FALSE)
                ));
            } else if (o instanceof com.e2eq.ontology.model.OntologyEdge e) {
                edgeSummaries.add(Map.of(
                        "p", e.getP(),
                        "dst", e.getDst(),
                        "inferred", e.isInferred()
                ));
            }
        }
        rc.put("edges", edgeSummaries);
        return rc;
    }
}
