package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.OntologyEdgeLookup;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.query.filters.Filter;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * {@link OntologyEdgeLookup} over {@link ComputedEdgeReader}, so query {@code hasEdge} /
 * {@code hasIncomingEdge} see LAZY and ONDEMAND computed edges as well as stored ones. The realm is
 * the caller's default realm, else the DataDomain's tenant (the edge store's own rule).
 */
@ApplicationScoped
@Unremovable // looked up only through CDI.current() by the query listeners
public class ComputedEdgeLookup implements OntologyEdgeLookup {

    @Inject
    ComputedEdgeReader reader;

    @Override
    public Set<String> srcIdsByDst(DataDomain dataDomain, String predicate, String dst, Filter edgeFilter) {
        return edgeFilter != null
                ? reader.srcIdsByDst(realm(dataDomain), dataDomain, predicate, dst, edgeFilter)
                : reader.srcIdsByDst(realm(dataDomain), dataDomain, predicate, dst);
    }

    @Override
    public Set<String> dstIdsBySrc(DataDomain dataDomain, String predicate, String src, Filter edgeFilter) {
        return edgeFilter != null
                ? reader.dstIdsBySrc(realm(dataDomain), dataDomain, predicate, src, edgeFilter)
                : reader.dstIdsBySrc(realm(dataDomain), dataDomain, predicate, src);
    }

    static String realm(DataDomain dataDomain) {
        String raw = SecurityContext.getPrincipalContext()
                .map(p -> p.getDefaultRealm())
                .filter(r -> !r.isBlank())
                .orElse(dataDomain != null ? dataDomain.getTenantId() : null);
        return ComputedEdgeReader.normalizeRealmId(raw);
    }
}
