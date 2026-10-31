package com.e2eq.ontology.core;

import java.util.List;
import java.util.Optional;

/**
 * Application-supplied runtime backing a {@link DeclarativeComputedEdgeProvider}.
 *
 * <p>The framework can parse a {@code computed:} YAML block into
 * {@link ComputedEdgeSpec}s, but it cannot load entities by itself — that
 * needs the application's repository wiring. Implement this for each spec
 * (or a single multi-type implementation) and register it as a CDI bean.</p>
 *
 * @param <S> source type
 * @param <H> hierarchy node type
 * @param <T> target (list item) type
 */
public interface DeclarativeRuntime<S, H, T> {

    /** True if this runtime backs the given spec. */
    boolean supports(ComputedEdgeSpec spec);

    /** Read the assigned hierarchy-node IDs from a source entity. */
    List<String> assignedNodeIds(S source);

    /** Load a hierarchy node by ID. */
    Optional<H> loadHierarchyNode(String nodeId);

    /** Walk the hierarchy according to {@link ComputedEdgeSpec.Hierarchy#expand()}. */
    List<H> walkHierarchy(String nodeId, com.e2eq.ontology.annotations.DependsOn.Expand expand);

    /** Resolve the items the node's list points at. */
    List<T> resolveListItems(H node);

    /** Stable string id for a target item. */
    String extractTargetId(T target);

    /** The class of the source type. */
    Class<S> getSourceType();
}
