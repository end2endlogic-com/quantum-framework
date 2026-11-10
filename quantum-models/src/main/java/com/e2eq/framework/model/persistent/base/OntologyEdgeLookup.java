package com.e2eq.framework.model.persistent.base;

import dev.morphia.query.filters.Filter;

import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@code hasEdge} / {@code hasIncomingEdge} query predicates to entity ids.
 *
 * <p>The query listeners live below the ontology modules, so they reach the implementation (in
 * {@code quantum-ontology-mongo}) through this interface and CDI. The implementation honors every
 * materialization mode: EAGER edges come from the edge store, LAZY and ONDEMAND computed edges are
 * computed, so a predicate matches whether or not its edges were ever written.</p>
 */
public interface OntologyEdgeLookup {

    /**
     * Ids of sources with a {@code predicate} edge to {@code dst}.
     *
     * @param edgeFilter a filter over edge fields, or {@code null}
     */
    Set<String> srcIdsByDst(DataDomain dataDomain, String predicate, String dst, Filter edgeFilter);

    /**
     * Ids of destinations {@code src} has a {@code predicate} edge to.
     *
     * @param edgeFilter a filter over edge fields, or {@code null}
     */
    Set<String> dstIdsBySrc(DataDomain dataDomain, String predicate, String src, Filter edgeFilter);

    /** The installed implementation, or empty when the ontology module is not installed. */
    static Optional<OntologyEdgeLookup> current() {
        try {
            var selection = jakarta.enterprise.inject.spi.CDI.current().select(OntologyEdgeLookup.class);
            if (!selection.isUnsatisfied()) {
                return Optional.of(selection.get());
            }
        } catch (IllegalStateException noCdi) {
            // no CDI container
        }
        return Optional.empty();
    }
}
