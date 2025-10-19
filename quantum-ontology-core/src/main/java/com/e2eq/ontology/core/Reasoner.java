
package com.e2eq.ontology.core;

import java.util.*;

public interface Reasoner {
    InferenceResult infer(EntitySnapshot snapshot, OntologyRegistry registry);

    record EntitySnapshot(String tenantId, String entityId, String entityType,
                          List<Edge> explicitEdges) {}

    record Provenance(String rule, Map<String, Object> inputs) {}

    record Edge(String srcId, String p, String dstId, boolean inferred, Optional<Provenance> prov) {}

    record InferenceResult(Set<String> addTypes, Set<String> addLabels, List<Edge> addEdges, List<Edge> removeEdges) {
        public static InferenceResult empty() {
            return new InferenceResult(new HashSet<>(), new HashSet<>(), new ArrayList<>(), new ArrayList<>());
        }
    }
}
