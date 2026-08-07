package com.e2eq.ontology.core;

/**
 * How a {@code ComputedEdgeProvider}'s output is stored and read.
 *
 * <ul>
 *   <li>{@link #EAGER} — the framework's historical behavior. Edges are
 *       computed at the source-entity write boundary (see
 *       {@code OntologyWriteHook}) and persisted to {@code ontology_edges} so
 *       reads are pure database lookups.</li>
 *   <li>{@link #LAZY} — edges are not persisted at write time. The first read
 *       triggers a compute and the result is cached in
 *       {@code ComputedEdgeCache} for {@link ComputedEdgeProvider#getCacheTtlSeconds()}.
 *       A dependency change (or recomputation) invalidates the entry.</li>
 *   <li>{@link #ONDEMAND} — never cached, never persisted. Every read calls the
 *       provider. Choose this only for inexpensive providers where freshness
 *       beats latency.</li>
 * </ul>
 *
 * <p><b>Read-path requirement:</b> LAZY and ONDEMAND edges are invisible to
 * direct {@code OntologyEdgeRepo} queries. Callers that need the same edges
 * for both source-keyed and destination-keyed lookups (including
 * {@code hasEdge} / policy rewrites that use {@code srcIdsByDst}) must go
 * through the mode-aware read facade ({@code ComputedEdgeReader}) with a
 * registered source-entity loader and, for inverse lookups, a
 * {@code SourceEntityEnumerator}. Prefer EAGER when destination-side filters
 * are on a hot path.</p>
 */
public enum MaterializationMode {
    EAGER,
    LAZY,
    ONDEMAND
}
