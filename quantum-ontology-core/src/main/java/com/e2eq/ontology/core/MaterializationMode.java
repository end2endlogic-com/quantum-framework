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
 */
public enum MaterializationMode {
    EAGER,
    LAZY,
    ONDEMAND
}
