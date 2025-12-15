package com.e2eq.ontology.spi;

import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;

import java.util.List;

/**
 * SPI to contribute explicit ontology edges for a given entity instance.
 * Implementations should be registered as CDI beans (e.g., @ApplicationScoped)
 * in application/domain modules. The framework will discover them at runtime
 * and merge their edges with annotation-derived edges.
 * 
 * <p>All edges produced by providers are scoped to the entity's DataDomainInfo
 * to ensure proper isolation across organizations, accounts, and tenants.</p>
 */
public interface OntologyEdgeProvider {

    /**
     * @param entityType runtime class of the entity being persisted
     * @return true if this provider can contribute edges for the given type
     */
    boolean supports(Class<?> entityType);

    /**
     * Produce explicit edges for the given entity instance. The framework
     * will handle persistence, pruning, and inference based on these edges.
     * 
     * <p>Edges should be scoped to the provided DataDomainInfo. Implementations
     * must NOT create edges that cross data domain boundaries (e.g., linking
     * entities from different organizations or accounts).</p>
     *
     * @param realmId        realm/database identifier for the entity
     * @param dataDomainInfo data domain info (orgRefName, accountNum, tenantId, dataSegment)
     * @param entity         entity instance being persisted
     * @return list of explicit edges to apply
     */
    List<Reasoner.Edge> edges(String realmId, DataDomainInfo dataDomainInfo, Object entity);
}
