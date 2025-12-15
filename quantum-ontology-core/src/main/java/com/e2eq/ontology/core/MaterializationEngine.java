package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.core.Reasoner.*;

import java.util.*;

/**
 * Materialization engine that applies ontology rules to derive inferred edges.
 * Implements a fixpoint algorithm applying subPropertyOf, inverses, transitivity, and property chains.
 */
public final class MaterializationEngine {
    private final OntologyRegistry registry;
    private final int maxIterations;
    
    public MaterializationEngine(OntologyRegistry registry) {
        this(registry, 10);
    }
    
    public MaterializationEngine(OntologyRegistry registry, int maxIterations) {
        this.registry = registry;
        this.maxIterations = maxIterations;
    }
    
    /**
     * Materialize inferred edges from explicit edges using fixpoint iteration.
     * Returns all edges (explicit + inferred).
     */
    public Set<Edge> materialize(String tenantId, String entityId, String entityType, List<Edge> explicitEdges) {
        Set<Edge> allEdges = new LinkedHashSet<>(explicitEdges);
        Set<Edge> workingSet = new LinkedHashSet<>(explicitEdges);
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            Set<Edge> newEdges = new LinkedHashSet<>();
            
            // Apply rules to current working set
            newEdges.addAll(applySubPropertyOf(workingSet));
            newEdges.addAll(applyInverses(workingSet));
            newEdges.addAll(applySymmetric(workingSet));
            newEdges.addAll(applyTransitive(workingSet));
            newEdges.addAll(applyPropertyChains(workingSet));
            
            // Remove edges already in allEdges
            newEdges.removeAll(allEdges);
            
            if (newEdges.isEmpty()) {
                // Fixpoint reached
                break;
            }
            
            allEdges.addAll(newEdges);
            workingSet = newEdges; // Next iteration works on newly derived edges
        }
        
        return allEdges;
    }
    
    private Set<Edge> applySubPropertyOf(Set<Edge> edges) {
        Set<Edge> result = new LinkedHashSet<>();
        
        for (Edge e : edges) {
            Optional<PropertyDef> pDef = registry.propertyOf(e.p());
            if (pDef.isEmpty()) continue;
            
            for (String superP : pDef.get().subPropertyOf()) {
                Map<String, Object> inputs = Map.of(
                    "sub", e.p(),
                    "super", superP,
                    "edge", Map.of("src", e.srcId(), "p", e.p(), "dst", e.dstId())
                );
                Provenance prov = new Provenance("subPropertyOf", inputs);
                result.add(new Edge(e.srcId(), e.srcType(), superP, e.dstId(), e.dstType(), true, Optional.of(prov)));
            }
        }
        
        return result;
    }
    
    private Set<Edge> applyInverses(Set<Edge> edges) {
        Set<Edge> result = new LinkedHashSet<>();
        
        for (Edge e : edges) {
            Optional<String> inv = registry.inverseOf(e.p());
            if (inv.isPresent()) {
                Map<String, Object> inputs = Map.of(
                    "of", e.p(),
                    "edge", Map.of("src", e.srcId(), "p", e.p(), "dst", e.dstId())
                );
                Provenance prov = new Provenance("inverse", inputs);
                result.add(new Edge(e.dstId(), e.dstType(), inv.get(), e.srcId(), e.srcType(), true, Optional.of(prov)));
            }
        }
        
        return result;
    }
    
    private Set<Edge> applySymmetric(Set<Edge> edges) {
        Set<Edge> result = new LinkedHashSet<>();
        
        for (Edge e : edges) {
            Optional<PropertyDef> pDef = registry.propertyOf(e.p());
            if (pDef.isPresent() && pDef.get().symmetric()) {
                Map<String, Object> inputs = Map.of(
                    "prop", e.p(),
                    "edge", Map.of("src", e.srcId(), "p", e.p(), "dst", e.dstId())
                );
                Provenance prov = new Provenance("symmetric", inputs);
                result.add(new Edge(e.dstId(), e.dstType(), e.p(), e.srcId(), e.srcType(), true, Optional.of(prov)));
            }
        }
        
        return result;
    }
    
    private Set<Edge> applyTransitive(Set<Edge> edges) {
        Set<Edge> result = new LinkedHashSet<>();
        
        // Group edges by predicate
        Map<String, List<Edge>> byPredicate = new HashMap<>();
        for (Edge e : edges) {
            byPredicate.computeIfAbsent(e.p(), k -> new ArrayList<>()).add(e);
        }
        
        for (Map.Entry<String, List<Edge>> entry : byPredicate.entrySet()) {
            String p = entry.getKey();
            Optional<PropertyDef> pDef = registry.propertyOf(p);
            if (pDef.isEmpty() || !pDef.get().transitive()) continue;
            
            // Build adjacency map
            Map<String, Set<String>> adj = new HashMap<>();
            for (Edge e : entry.getValue()) {
                adj.computeIfAbsent(e.srcId(), k -> new HashSet<>()).add(e.dstId());
            }
            
            // Compute transitive closure
            for (Edge e1 : entry.getValue()) {
                Set<String> reachable = new HashSet<>();
                Deque<String> queue = new ArrayDeque<>();
                queue.add(e1.dstId());
                
                while (!queue.isEmpty()) {
                    String current = queue.poll();
                    for (String next : adj.getOrDefault(current, Set.of())) {
                        if (reachable.add(next)) {
                            queue.add(next);
                            
                            Map<String, Object> inputs = Map.of(
                                "prop", p,
                                "path", List.of(e1.srcId(), current, next)
                            );
                            Provenance prov = new Provenance("transitive", inputs);
                            result.add(new Edge(e1.srcId(), e1.srcType(), p, next, e1.dstType(), true, Optional.of(prov)));
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    private Set<Edge> applyPropertyChains(Set<Edge> edges) {
        Set<Edge> result = new LinkedHashSet<>();
        
        // Build adjacency per predicate
        Map<String, Map<String, Set<String>>> adj = new HashMap<>();
        Map<String, String> nodeTypes = new HashMap<>();
        
        for (Edge e : edges) {
            adj.computeIfAbsent(e.p(), k -> new HashMap<>())
               .computeIfAbsent(e.srcId(), k -> new HashSet<>())
               .add(e.dstId());
            nodeTypes.putIfAbsent(e.srcId(), e.srcType());
            nodeTypes.putIfAbsent(e.dstId(), e.dstType());
        }
        
        for (PropertyChainDef chain : registry.propertyChains()) {
            if (chain.chain().size() < 2) continue;
            
            // Find all paths matching the chain
            Set<String> frontier = new HashSet<>(nodeTypes.keySet());
            
            for (int i = 0; i < chain.chain().size(); i++) {
                String p = chain.chain().get(i);
                Set<String> nextFrontier = new HashSet<>();
                
                for (String node : frontier) {
                    Set<String> targets = adj.getOrDefault(p, Map.of()).getOrDefault(node, Set.of());
                    nextFrontier.addAll(targets);
                }
                
                if (i == 0) {
                    // Track starting nodes for final edge creation
                    Map<String, Set<String>> startToEnd = new HashMap<>();
                    for (String start : frontier) {
                        for (String end : nextFrontier) {
                            startToEnd.computeIfAbsent(start, k -> new HashSet<>()).add(end);
                        }
                    }
                    
                    if (i == chain.chain().size() - 1) {
                        // Create inferred edges
                        for (Map.Entry<String, Set<String>> e : startToEnd.entrySet()) {
                            String src = e.getKey();
                            for (String dst : e.getValue()) {
                                Map<String, Object> inputs = Map.of(
                                    "chain", chain.chain(),
                                    "src", src,
                                    "dst", dst
                                );
                                Provenance prov = new Provenance("chain", inputs);
                                String srcType = nodeTypes.getOrDefault(src, "Unknown");
                                String dstType = nodeTypes.getOrDefault(dst, "Unknown");
                                result.add(new Edge(src, srcType, chain.implies(), dst, dstType, true, Optional.of(prov)));
                            }
                        }
                    }
                }
                
                frontier = nextFrontier;
                if (frontier.isEmpty()) break;
            }
        }
        
        return result;
    }
}
