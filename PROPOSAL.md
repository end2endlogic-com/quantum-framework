### Proposal: Turnkey Ontology Integration in Quantum

This document is a concise, repo-local pointer to the full proposal for integrating the ontology modules with Morphia, permissions, and multi-tenancy. It summarizes the intent and links you to the exact modules/classes involved so you can review quickly.

#### Why this exists
Some readers missed the proposal inside the long-form documentation. This file provides an at-a-glance view and module-level checklist.

---

### Scope and goals
- Make semantic relationships first-class using the ontology modules (core, mongo, policy bridge).
- Keep developer ergonomics: small helper APIs, minimal changes to existing repos/services.
- Ensure multi-tenant safety and good performance via pre-materialized edges and proper indexes.

### Modules and key changes (design-level)

1) quantum-ontology-core
- Extend Reasoner API to support provenance and removals.
  - record Provenance(String rule, Map<String, Object> inputs)
  - record Edge(String srcId, String p, String dstId, boolean inferred, Optional<Provenance> prov)
  - record InferenceResult(Set<String> addTypes, Set<String> addLabels, List<Edge> addEdges, List<Edge> removeEdges)
  - static InferenceResult.empty()
- ForwardChainingReasoner additions:
  - Property chains of length N (>= 2), inverses, and transitive properties.
  - Populate provenance (rule=chain|inverse|transitive; inputs={chain:[...], via:[...]})
- Optional utility: OntologyRegistryUtil.validate(...) and a small Builder for in-memory TBox.

2) quantum-ontology-mongo
- EdgeDoc POJO for edges persisted to Mongo.
- EdgeDao API for indexes, bulk upsert, and pruning obsolete inferred edges:
  - ensureIndexes(), upsertMany(...), deleteBySrc(...), deleteBySrcAndPredicate(...),
    deleteInferredBySrcNotIn(...), srcIdsByDstIn(...), srcIdsByDstGrouped(...)
- OntologyMaterializer wiring:
  - Constructor accepts Reasoner, OntologyRegistry, EdgeDao.
  - apply(...): run reasoner, diff vs current inferred edges, upsert new, delete obsolete.
  - applyBatch(...), recomputeForSources(...)

3) quantum-ontology-policy-bridge
- ListQueryRewriter convenience methods to translate ontology constraints into efficient queries:
  - rewriteForHasEdge(...), rewriteForHasEdgeAny(...), rewriteForHasEdgeAll(...), rewriteForNotHasEdge(...)
  - idsForHasEdge(...), idsForHasEdgeAny(...)

4) quantum-morphia-repos
- OntologyFilterHelper to apply ontology constraints in repos with minimal boilerplate.
- Morphia query-language hook (optional):
  - QueryFunctionEvaluator SPI + HasEdgeFunctionEvaluator to support hasEdge("predicate", value) in filter strings.

5) quantum-framework (wiring)
- OntologyConfig to wire registry → reasoner → materializer → rewriter and register evaluators on startup.
- Optional rule-language function HasEdgeRuleFunction and predicate whitelist.

6) Models/hooks
- ExplicitEdgeProvider interface; call materializer.apply(...) from save/update flows.

7) Operational
- Indexes: (tenantId, p, dst) and (tenantId, src, p); optional TTL on ts for inferred edges.

8) End-to-end test (integration)
- Build a tiny TBox (placedBy/memberOf/ancestorOf), insert minimal docs, materialize edges, and query via hasEdge.

---

### Current state in this repo
- Documentation: The long-form write-up and concrete e‑commerce example live in quantum-docs/src/docs/asciidoc/user-guide/modeling.adoc under sections:
  - "Ontologies in Quantum: Modeling Relationships That Are Resilient and Fast"
  - "Concrete example: Sales Orders, Shipments, and evolving to Fulfillment/Returns"
  - "Integrating Ontology with Morphia, Permissions, and Multi-tenancy"
- Code stubs present:
  - Core: com.e2eq.ontology.core (OntologyRegistry, Reasoner, ForwardChainingReasoner)
  - Mongo: com.e2eq.ontology.mongo (EdgeDao, OntologyMaterializer)
  - Policy bridge: com.e2eq.ontology.policy (ListQueryRewriter)

These stubs demonstrate the direction and allow basic end-to-end flows. The extended features listed above are the proposed next steps.

---

### Minimal next steps (if you approve)
- Implement the provenance-aware Reasoner API and ForwardChainingReasoner features.
- Flesh out EdgeDao with bulk and pruning operations; add ensureIndexes().
- Update OntologyMaterializer to perform diffing writes using EdgeDao’s new methods.
- Add ListQueryRewriter set-based helpers and a tiny OntologyFilterHelper for repos.
- Wire everything in a small OntologyConfig and add one end-to-end test.

### Why this helps
- Decouples query logic from object graphs; makes list/policy queries faster and more resilient to model evolution.
- Keeps developer experience simple: a single helper or function handles ontology-based constraints.

---

### Pointers to code
- quantum-ontology-core/src/main/java/com/e2eq/ontology/core/
- quantum-ontology-mongo/src/main/java/com/e2eq/ontology/mongo/
- quantum-ontology-policy-bridge/src/main/java/com/e2eq/ontology/policy/
- quantum-morphia-repos/src/main/java/com/e2eq/framework/model/persistent/morphia/
- quantum-framework/src/main/java/com/e2eq/framework/

If you’d like this split into separate smaller PRs per module, say the word and I’ll proceed accordingly.
