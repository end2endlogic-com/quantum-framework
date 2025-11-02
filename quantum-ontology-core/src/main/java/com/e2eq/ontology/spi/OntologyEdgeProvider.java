package com.e2eq.ontology.spi;

import com.e2eq.ontology.core.Reasoner;

import java.util.List;

/**
 * SPI to contribute explicit ontology edges for a given entity instance.
 * Implementations should be registered as CDI beans (e.g., @ApplicationScoped)
 * in application/domain modules. The framework will discover them at runtime
 * and merge their edges with annotation-derived edges.
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
     * @param realmId tenant/realm identifier
     * @param entity  entity instance being persisted
     * @return list of explicit edges to apply
     */
    List<Reasoner.Edge> edges(String realmId, Object entity);
}
