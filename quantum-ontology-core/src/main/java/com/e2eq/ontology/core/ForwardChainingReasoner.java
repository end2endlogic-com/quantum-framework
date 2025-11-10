
package com.e2eq.ontology.core;

import java.util.*;
import com.e2eq.ontology.core.OntologyRegistry.*;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public final class ForwardChainingReasoner implements Reasoner {

    @Override
    public InferenceResult infer(EntitySnapshot snap, OntologyRegistry reg) {
        Set<String> types = new HashSet<>();
        Set<String> labels = new HashSet<>();
        List<Edge> edges = new ArrayList<>();

        Map<String, String> nodeTypes = new HashMap<>();
        if (snap.entityType() != null && !snap.entityType().isBlank()) {
            nodeTypes.put(snap.entityId(), snap.entityType());
        }
        for (Edge e : snap.explicitEdges()) {
            if (e.srcType() != null && !e.srcType().isBlank()) {
                nodeTypes.putIfAbsent(e.srcId(), e.srcType());
            }
            if (e.dstType() != null && !e.dstType().isBlank()) {
                nodeTypes.putIfAbsent(e.dstId(), e.dstType());
            }
        }

        if (reg == null) {
            return new InferenceResult(types, labels, edges, new ArrayList<>());
        }

        // 1) domain typing from explicit edges
        for (Edge e : snap.explicitEdges()) {
            reg.propertyOf(e.p()).ifPresent(prop -> prop.domain().ifPresent(types::add));
        }

        // Build adjacency per predicate for explicit edges
        Map<String, Map<String, Set<String>>> outByP = new HashMap<>(); // p -> src -> dsts
        Map<String, Map<String, Set<String>>> inByP = new HashMap<>(); // p -> dst -> srcs
        for (Edge e : snap.explicitEdges()) {
            outByP.computeIfAbsent(e.p(), k -> new HashMap<>())
                    .computeIfAbsent(e.srcId(), k -> new HashSet<>()).add(e.dstId());
            inByP.computeIfAbsent(e.p(), k -> new HashMap<>())
                    .computeIfAbsent(e.dstId(), k -> new HashSet<>()).add(e.srcId());
        }

        // 2) Property chains of length N >= 2 using forward chaining bounded to snapshot graph
        //    Integrate transitive expansion for predicates marked transitive and make newly implied
        //    edges available to subsequent chain evaluations within this pass by updating adjacency.
        final int MAX_TRANSITIVE_STEP_VISITS = 10_000;
        for (PropertyChainDef ch : reg.propertyChains()) {
            List<String> chain = ch.chain();
            if (chain == null || chain.size() < 2) continue;
            String implies = ch.implies();
            // paths start at snap.entityId
            Set<String> frontier = new HashSet<>();
            frontier.add(snap.entityId());
            // For provenance inputs: collect contributing hops for this source
            List<Map<String, Object>> contributingHops = new ArrayList<>();
            Set<String> reached = new HashSet<>();
            // iterate along chain predicates
            for (int i = 0; i < chain.size(); i++) {
                String p = chain.get(i);
                boolean isTransitive = reg.propertyOf(p).map(PropertyDef::transitive).orElse(false);
                Set<String> nextFrontier = new HashSet<>();

                if (isTransitive) {
                    // BFS closure over p starting from current frontier
                    Deque<String> dq = new ArrayDeque<>(frontier);
                    Set<String> visited = new HashSet<>(frontier);
                    int visits = 0;
                    while (!dq.isEmpty() && visits < MAX_TRANSITIVE_STEP_VISITS) {
                        String x = dq.removeFirst();
                        visits++;
                        Set<String> ys = outByP.getOrDefault(p, Map.of()).getOrDefault(x, Set.of());
                        for (String y : ys) {
                            // capture hop for provenance
                            Map<String, Object> hop = new HashMap<>();
                            hop.put("src", x);
                            hop.put("p", p);
                            hop.put("dst", y);
                            String hopSrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                            String hopDstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                            if (hopSrcType != null) hop.put("srcType", hopSrcType);
                            if (hopDstType != null) hop.put("dstType", hopDstType);
                            contributingHops.add(hop);

                            if (visited.add(y)) {
                                dq.addLast(y);
                            }
                            nextFrontier.add(y);
                        }
                    }
                } else {
                    for (String x : frontier) {
                        Set<String> ys = outByP.getOrDefault(p, Map.of()).getOrDefault(x, Set.of());
                        for (String y : ys) {
                            nextFrontier.add(y);
                            // capture hop for provenance (include type information if available)
                            Map<String, Object> hop = new HashMap<>();
                            hop.put("src", x);
                            hop.put("p", p);
                            hop.put("dst", y);
                            String hopSrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                            String hopDstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                            if (hopSrcType != null) hop.put("srcType", hopSrcType);
                            if (hopDstType != null) hop.put("dstType", hopDstType);
                            contributingHops.add(hop);
                        }
                    }
                }

                frontier = nextFrontier;
                if (frontier.isEmpty()) break;
                if (i == chain.size() - 1) {
                    reached.addAll(frontier);
                }
            }
            for (String z : reached) {
                if (z.equals(snap.entityId())) continue;
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("chain", chain);
                inputs.put("inputs", contributingHops);
                Provenance prov = new Provenance("chain", inputs);
                String srcType = resolveSourceType(reg, nodeTypes, implies, snap.entityId(), snap.entityType());
                String dstType = resolveDestinationType(reg, nodeTypes, implies, z, null);
                // Add inferred edge
                edges.add(new Edge(snap.entityId(), srcType, implies, z, dstType, true, Optional.of(prov)));
                // Make it available to subsequent chain evaluations in this pass
                outByP.computeIfAbsent(implies, k -> new HashMap<>())
                      .computeIfAbsent(snap.entityId(), k -> new HashSet<>())
                      .add(z);
            }
        }

        // 3) SubPropertyOf: materialize super-property edges (s, q, o) for each q in p.subPropertyOf
        for (Map.Entry<String, Map<String, Set<String>>> entry : outByP.entrySet()) {
            String p = entry.getKey();
            Optional<PropertyDef> pDef = reg.propertyOf(p);
            if (pDef.isPresent()) {
                Set<String> supers = Optional.ofNullable(pDef.get().subPropertyOf()).orElse(Set.of());
                if (!supers.isEmpty()) {
                    for (String superP : supers) {
                        for (Map.Entry<String, Set<String>> e1 : entry.getValue().entrySet()) {
                            String x = e1.getKey();
                            for (String y : e1.getValue()) {
                                Map<String, Object> inputs = new HashMap<>();
                                inputs.put("sub", p);
                                inputs.put("super", superP);
                                Map<String, Object> inEdge = new HashMap<>();
                                inEdge.put("src", x);
                                inEdge.put("p", p);
                                inEdge.put("dst", y);
                                String inSrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                                String inDstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                                if (inSrcType != null) inEdge.put("srcType", inSrcType);
                                if (inDstType != null) inEdge.put("dstType", inDstType);
                                inputs.put("inputs", List.of(inEdge));
                                Provenance prov = new Provenance("subPropertyOf", inputs);
                                String srcType = resolveSourceType(reg, nodeTypes, superP, x, null);
                                String dstType = resolveDestinationType(reg, nodeTypes, superP, y, null);
                                edges.add(new Edge(x, srcType, superP, y, dstType, true, Optional.of(prov)));
                            }
                        }
                    }
                }
            }
        }

        // 4) Inverses and symmetric: if property has an inverseOf or symmetric, add corresponding edges
        for (Map.Entry<String, Map<String, Set<String>>> entry : outByP.entrySet()) {
            String p = entry.getKey();
            Optional<PropertyDef> pDef = reg.propertyOf(p);
            if (pDef.isEmpty()) continue;
            Optional<String> inv = pDef.get().inverseOf();
            // If no direct inverse is declared on this property, search for a property that declares this as its inverse
            if (inv.isEmpty()) {
                for (PropertyDef q : reg.properties().values()) {
                    if (q.inverseOf().isPresent() && q.inverseOf().get().equals(p)) {
                        inv = Optional.of(q.name());
                        break;
                    }
                }
            }
            boolean symmetric = pDef.get().symmetric();
            for (Map.Entry<String, Set<String>> e1 : entry.getValue().entrySet()) {
                String x = e1.getKey();
                for (String y : e1.getValue()) {
                    if (inv.isPresent()) {
                        Map<String, Object> inputs = new HashMap<>();
                        inputs.put("of", p);
                        Map<String, Object> inEdge = new HashMap<>();
                        inEdge.put("src", x);
                        inEdge.put("p", p);
                        inEdge.put("dst", y);
                        String inSrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                        String inDstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                        if (inSrcType != null) inEdge.put("srcType", inSrcType);
                        if (inDstType != null) inEdge.put("dstType", inDstType);
                        inputs.put("inputs", List.of(inEdge));
                        Provenance prov = new Provenance("inverse", inputs);
                        String invSrcType = resolveSourceType(reg, nodeTypes, inv.get(), y, null);
                        String invDstType = resolveDestinationType(reg, nodeTypes, inv.get(), x, null);
                        edges.add(new Edge(y, invSrcType, inv.get(), x, invDstType, true, Optional.of(prov)));
                    }
                    if (symmetric) {
                        Map<String, Object> inputs2 = new HashMap<>();
                        inputs2.put("prop", p);
                        Map<String, Object> inEdge2 = new HashMap<>();
                        inEdge2.put("src", x);
                        inEdge2.put("p", p);
                        inEdge2.put("dst", y);
                        String in2SrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                        String in2DstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                        if (in2SrcType != null) inEdge2.put("srcType", in2SrcType);
                        if (in2DstType != null) inEdge2.put("dstType", in2DstType);
                        inputs2.put("inputs", List.of(inEdge2));
                        Provenance prov2 = new Provenance("symmetric", inputs2);
                        String symSrcType = resolveSourceType(reg, nodeTypes, p, y, null);
                        String symDstType = resolveDestinationType(reg, nodeTypes, p, x, null);
                        edges.add(new Edge(y, symSrcType, p, x, symDstType, true, Optional.of(prov2)));
                    }
                }
            }
        }

        // 5) Transitive closure per transitive property (limited to explicit edges in snapshot)
        for (Map.Entry<String, Map<String, Set<String>>> entry : outByP.entrySet()) {
            String p = entry.getKey();
            Optional<PropertyDef> def = reg.propertyOf(p);
            if (def.isEmpty() || !def.get().transitive()) continue;
            // BFS from snap.entityId along p
            Set<String> visited = new HashSet<>();
            Deque<String> dq = new ArrayDeque<>();
            dq.add(snap.entityId());
            visited.add(snap.entityId());
            // Track predecessor to reconstruct minimal path input for provenance
            Map<String, String> prev = new HashMap<>();
            while (!dq.isEmpty()) {
                String x = dq.removeFirst();
                for (String y : outByP.getOrDefault(p, Map.of()).getOrDefault(x, Set.of())) {
                    if (visited.add(y)) {
                        prev.put(y, x);
                        dq.addLast(y);
                    }
                    if (!y.equals(snap.entityId())) {
                        // reconstruct simple path S -> ... -> y (we only record the last hop for compactness)
                        List<Map<String, Object>> inputs = new ArrayList<>();
                        Map<String, Object> inEdge = new HashMap<>();
                        inEdge.put("src", x);
                        inEdge.put("p", p);
                        inEdge.put("dst", y);
                        String inSrcType = resolveSourceTypeOptional(reg, nodeTypes, p, x, x.equals(snap.entityId()) ? snap.entityType() : null);
                        String inDstType = resolveDestinationTypeOptional(reg, nodeTypes, p, y, null);
                        if (inSrcType != null) inEdge.put("srcType", inSrcType);
                        if (inDstType != null) inEdge.put("dstType", inDstType);
                        inputs.add(inEdge);
                        Map<String, Object> provInputs = new HashMap<>();
                        provInputs.put("prop", p);
                        provInputs.put("inputs", inputs);
                        Provenance prov = new Provenance("transitive", provInputs);
                        String srcType = resolveSourceType(reg, nodeTypes, p, snap.entityId(), snap.entityType());
                        String dstType = resolveDestinationType(reg, nodeTypes, p, y, null);
                        edges.add(new Edge(snap.entityId(), srcType, p, y, dstType, true, Optional.of(prov)));
                    }
                }
            }
        }

        return new InferenceResult(types, labels, edges, new ArrayList<>());
    }

    private String resolveSourceType(OntologyRegistry reg,
                                     Map<String, String> nodeTypes,
                                     String property,
                                     String nodeId,
                                     String fallback) {
        Optional<String> declared = Optional.empty();
        if (reg != null) {
            declared = reg.propertyOf(property).flatMap(OntologyRegistry.PropertyDef::domain);
        }
        return resolveType(declared, nodeTypes, nodeId, fallback, "source", property);
    }

    private String resolveDestinationType(OntologyRegistry reg,
                                          Map<String, String> nodeTypes,
                                          String property,
                                          String nodeId,
                                          String fallback) {
        Optional<String> declared = Optional.empty();
        if (reg != null) {
            declared = reg.propertyOf(property).flatMap(OntologyRegistry.PropertyDef::range);
        }
        return resolveType(declared, nodeTypes, nodeId, fallback, "destination", property);
    }

    // Optional type resolvers for provenance enrichment that avoid throwing
    private String resolveSourceTypeOptional(OntologyRegistry reg,
                                             Map<String, String> nodeTypes,
                                             String property,
                                             String nodeId,
                                             String fallback) {
        Optional<String> declared = Optional.empty();
        if (reg != null) {
            declared = reg.propertyOf(property).flatMap(OntologyRegistry.PropertyDef::domain);
        }
        return resolveTypeOptional(declared, nodeTypes, nodeId, fallback);
    }

    private String resolveDestinationTypeOptional(OntologyRegistry reg,
                                                  Map<String, String> nodeTypes,
                                                  String property,
                                                  String nodeId,
                                                  String fallback) {
        Optional<String> declared = Optional.empty();
        if (reg != null) {
            declared = reg.propertyOf(property).flatMap(OntologyRegistry.PropertyDef::range);
        }
        return resolveTypeOptional(declared, nodeTypes, nodeId, fallback);
    }

    private String resolveType(Optional<String> declared,
                               Map<String, String> nodeTypes,
                               String nodeId,
                               String fallback,
                               String role,
                               String property) {
        if (declared.isPresent() && !declared.get().isBlank()) {
            return declared.get();
        }
        String fromMap = nodeTypes.get(nodeId);
        if (fromMap != null && !fromMap.isBlank()) {
            return fromMap;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        throw new IllegalStateException("Unable to resolve " + role + " type for property '" + property + "' and node '" + nodeId + "'");
    }

    private String resolveTypeOptional(Optional<String> declared,
                                       Map<String, String> nodeTypes,
                                       String nodeId,
                                       String fallback) {
        if (declared.isPresent() && !declared.get().isBlank()) {
            return declared.get();
        }
        String fromMap = nodeTypes.get(nodeId);
        if (fromMap != null && !fromMap.isBlank()) {
            return fromMap;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
