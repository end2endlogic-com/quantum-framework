
package com.e2eq.ontology.mongo;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.inject.Inject;

@ApplicationScoped
public class OntologyContextEnricherMongo {

   @Inject
    protected OntologyEdgeRepo edgeRepo;

    public Map<String,Object> enrich(String tenantId, String resourceId){
        Map<String,Object> rc = new HashMap<>();
        List<OntologyEdge> edges = edgeRepo.findBySrc(tenantId, resourceId);
        List<Map<String,Object>> edgeSummaries = new ArrayList<>();
        for (OntologyEdge e : edges){
            edgeSummaries.add(Map.of(
                    "p", e.getP(),
                    "dst", e.getDst(),
                    "inferred", e.isInferred()
            ));
        }
        rc.put("edges", edgeSummaries);
        return rc;
    }
}
