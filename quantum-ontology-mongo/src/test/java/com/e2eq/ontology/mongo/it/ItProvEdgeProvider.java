package com.e2eq.ontology.mongo.it;

import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ItProvEdgeProvider implements OntologyEdgeProvider {

    @Override
    public boolean supports(Class<?> entityType) {
        return ItProvSource.class.isAssignableFrom(entityType);
    }

    @Override
    public List<Reasoner.Edge> edges(String realmId, DataDomainInfo dataDomainInfo, Object entity) {
        ItProvSource src = (ItProvSource) entity;
        List<Reasoner.Edge> out = new ArrayList<>();
        // Add an extra explicit edge from source to a target identified only by refName string
        String extraRef = src.getProviderTargetRef();
        if (extraRef != null && !extraRef.isBlank()) {
            // srcId and srcType are determined by framework; we set here based on conventions
            String srcId = src.getRefName();
            if (srcId != null && !srcId.isBlank()) {
                // Note: dataDomainInfo is available for any DataDomain-scoped filtering if needed
                out.add(new Reasoner.Edge(srcId, "ProvSource", "provRel", extraRef, "ProvTgt", false, java.util.Optional.empty()));
            }
        }
        return out;
    }
}
