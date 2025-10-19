
package com.e2eq.ontology.mongo;

import java.util.*;
import org.bson.Document;

public class OntologyContextEnricherMongo {

    private final EdgeDao edgeDao;

    public OntologyContextEnricherMongo(EdgeDao edgeDao){
        this.edgeDao = edgeDao;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> enrich(String tenantId, String resourceId){
        Map<String,Object> rc = new HashMap<>();
        var edges = edgeDao.findBySrc(tenantId, resourceId);
        List<Map<String,Object>> edgeSummaries = new ArrayList<>();
        for (Document d : edges){
            edgeSummaries.add(Map.of(
                "p", d.getString("p"),
                "dst", d.getString("dst"),
                "inferred", d.getBoolean("inferred", Boolean.FALSE)
            ));
        }
        rc.put("edges", edgeSummaries);
        return rc;
    }
}
