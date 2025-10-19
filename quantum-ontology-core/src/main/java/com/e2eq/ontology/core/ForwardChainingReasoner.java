
package com.e2eq.ontology.core;

import java.util.*;
import com.e2eq.ontology.core.OntologyRegistry.*;
import static com.e2eq.ontology.core.Reasoner.*;

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

        // 3) Inverses: if current property has an inverseOf, add inverse edge (y, inverseOf, x)
        for (Map.Entry<String, Map<String, Set<String>>> entry : outByP.entrySet()) {
            String p = entry.getKey();
            Optional<PropertyDef> pDef = reg.propertyOf(p);
            if (pDef.isEmpty()) continue;
            Optional<String> inv = pDef.get().inverseOf();
            if (inv.isEmpty()) continue;
            String invName = inv.get();
            for (Map.Entry<String, Set<String>> e1 : entry.getValue().entrySet()) {
                String x = e1.getKey();
                for (String y : e1.getValue()) {
                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("of", p);
                    inputs.put("via", List.of(x, y));
                    Provenance prov = new Provenance("inverse", inputs);
                    edges.add(new Edge(y, invName, x, true, Optional.of(prov)));
                }
            }
        }

        // 4) Transitive closure per transitive property (limited to explicit edges in snapshot)
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

    // Fallback to iterate properties if registry is not InMemoryOntologyRegistry
    private Map<String, PropertyDef> regProperties(OntologyRegistry reg) {
        Map<String, PropertyDef> map = new HashMap<>();
        for (PropertyChainDef ch : reg.propertyChains()) {
            // no-op; we cannot enumerate props via API; return empty
        }
        return map; // we won't add inverse edges in this path
    }
}
