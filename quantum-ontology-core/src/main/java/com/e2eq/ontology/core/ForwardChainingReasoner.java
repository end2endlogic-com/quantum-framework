
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

        // 2) Property chains of length N >= 2 using simple forward chaining bounded to snapshot graph
        for (PropertyChainDef ch : reg.propertyChains()) {
            List<String> chain = ch.chain();
            if (chain == null || chain.size() < 2) continue;
            String implies = ch.implies();
            // paths start at snap.entityId
            Set<String> frontier = new HashSet<>();
            frontier.add(snap.entityId());
            List<String> viaPath = new ArrayList<>();
            viaPath.add(snap.entityId());
            Set<String> reached = new HashSet<>();
            // iterate along chain predicates
            for (int i = 0; i < chain.size(); i++) {
                String p = chain.get(i);
                Set<String> nextFrontier = new HashSet<>();
                for (String x : frontier) {
                    Set<String> ys = outByP.getOrDefault(p, Map.of()).getOrDefault(x, Set.of());
                    nextFrontier.addAll(ys);
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
                inputs.put("via", List.of());
                Provenance prov = new Provenance("chain", inputs);
                edges.add(new Edge(snap.entityId(), implies, z, true, Optional.of(prov)));
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
                                inputs.put("via", List.of(x, y));
                                Provenance prov = new Provenance("subPropertyOf", inputs);
                                edges.add(new Edge(x, superP, y, true, Optional.of(prov)));
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
                        inputs.put("via", List.of(x, y));
                        Provenance prov = new Provenance("inverse", inputs);
                        edges.add(new Edge(y, inv.get(), x, true, Optional.of(prov)));
                    }
                    if (symmetric) {
                        Map<String, Object> inputs2 = new HashMap<>();
                        inputs2.put("prop", p);
                        inputs2.put("via", List.of(x, y));
                        Provenance prov2 = new Provenance("symmetric", inputs2);
                        edges.add(new Edge(y, p, x, true, Optional.of(prov2)));
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
            while (!dq.isEmpty()) {
                String x = dq.removeFirst();
                for (String y : outByP.getOrDefault(p, Map.of()).getOrDefault(x, Set.of())) {
                    if (visited.add(y)) {
                        dq.addLast(y);
                    }
                    if (!y.equals(snap.entityId())) {
                        Map<String, Object> inputs = new HashMap<>();
                        inputs.put("prop", p);
                        inputs.put("via", List.of(x, y));
                        Provenance prov = new Provenance("transitive", inputs);
                        edges.add(new Edge(snap.entityId(), p, y, true, Optional.of(prov)));
                    }
                }
            }
        }

        return new InferenceResult(types, labels, edges, new ArrayList<>());
    }


}
