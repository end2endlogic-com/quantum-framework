package com.e2eq.ontology.core;

import java.util.Date;
import java.util.List;

/**
 * Provenance information for a computed edge, tracking which entities
 * and data sources contributed to its computation.
 *
 * <p>This record captures the full lineage of how a computed edge was derived,
 * including:</p>
 * <ul>
 *   <li>Which provider computed the edge</li>
 *   <li>The source entity that triggered the computation</li>
 *   <li>Hierarchy nodes traversed (for hierarchy-based computations)</li>
 *   <li>Lists resolved during computation (static or dynamic)</li>
 * </ul>
 *
 * <p>This provenance is stored with the edge and can be used for:</p>
 * <ul>
 *   <li>Debugging - understanding why an edge exists</li>
 *   <li>Incremental updates - knowing what to recompute when dependencies change</li>
 *   <li>Auditing - tracking data lineage</li>
 * </ul>
 */
public record ComputedEdgeProvenance(
    /** Unique identifier for the provider that created this edge */
    String providerId,

    /** The source entity type that triggered computation */
    String sourceEntityType,

    /** The source entity ID that triggered computation */
    String sourceEntityId,

    /** Hierarchy nodes traversed during computation (may be empty) */
    List<HierarchyContribution> hierarchyPath,

    /** Lists resolved during computation (may be empty) */
    List<ListContribution> resolvedLists,

    /** Timestamp when the edge was computed */
    Date computedAt
) {

    /**
     * A hierarchy node that contributed to the computed edge.
     *
     * <p>When edges are computed by traversing a hierarchy (e.g., Territory tree),
     * each node in the traversal path is recorded here.</p>
     */
    public record HierarchyContribution(
        /** The unique ID of the hierarchy node */
        String nodeId,

        /** The type name of the hierarchy node (e.g., "Territory") */
        String nodeType,

        /** The reference name of the hierarchy node (human-readable identifier) */
        String nodeRefName,

        /** True if directly assigned to source, false if inherited via hierarchy */
        boolean isDirectAssignment
    ) {}

    /**
     * A list (static or dynamic) that contributed to the computed edge.
     *
     * <p>When edges are computed by resolving StaticDynamicLists, each list
     * that was resolved is recorded here.</p>
     */
    public record ListContribution(
        /** The unique ID of the list */
        String listId,

        /** The type name of the list (e.g., "LocationList") */
        String listType,

        /** The mode of the list (STATIC or DYNAMIC) */
        String mode,

        /** The filter string if dynamic (null for static lists) */
        String filterString,

        /** The number of items resolved from this list */
        int itemCount
    ) {}

    /**
     * Creates a minimal provenance with just the provider ID and timestamp.
     */
    public static ComputedEdgeProvenance minimal(String providerId) {
        return new ComputedEdgeProvenance(
            providerId,
            null,
            null,
            List.of(),
            List.of(),
            new Date()
        );
    }

    /**
     * Creates a copy with updated source entity information.
     */
    public ComputedEdgeProvenance withSourceEntity(String sourceType, String sourceId) {
        return new ComputedEdgeProvenance(
            this.providerId,
            sourceType,
            sourceId,
            this.hierarchyPath,
            this.resolvedLists,
            this.computedAt
        );
    }
}
