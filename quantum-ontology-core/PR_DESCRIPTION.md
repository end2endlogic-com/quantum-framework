
# Ontology-Based Reasoning: INDIRECTLY_SERVES (Shipment → Order → Customer)

## Summary
This PR introduces a minimal Ontology-Based Reasoning (OBR) layer and integrates it
with the existing policy rule engine—without changing the rule DSL. It enables a
high-impact scenario: authorizing CSR list queries for **Shipments that indirectly
serve a Customer** via the property chain `FULFILLS ∘ PLACED_BY → INDIRECTLY_SERVES`.

## What’s included
- `quantum-ontology-core`: TBox registry + forward-chaining reasoner (subclass, domain/range, inverse,
  transitive, chain length 2).
- `quantum-ontology-mongo`: edge materialization (Mongo) + context enricher.
- `quantum-ontology-policy-bridge`: policy script helpers + list query rewriter hooks.
- Example TBox JSON with classes (`Shipment`, `Order`, `Customer`) and the chain definition.
- Example YAML rule for CSR list authorization (uses `hasEdge` helper).

## Why
- Centralize semantics once (property chain) and reuse everywhere (policy, queries, UI).
- Avoid hand-coded joins in each endpoint. Reads stay fast via materialized `edges` with indexes.
- No change to rule DSL—just richer `rcontext` and helper functions.

## Implementation details
- Materialize inferred edge: `(:Shipment)-[:INDIRECTLY_SERVES]->(:Customer)` with provenance.
- Enrich `rcontext` with inferred edges/types so rules can call `hasEdge('INDIRECTLY_SERVES', pcontext.partyId)`.
- List rewriter compiles helper usage into index-friendly Mongo predicates (`_id ∈ srcIds` from `edges` lookup).

## Ops
- New indexes:
  - Entities: `{ tenantId:1, types:1 }`, `{ tenantId:1, labels:1 }`
  - Edges: `{ tenantId:1, p:1, dst:1 }`, `{ tenantId:1, p:1, src:1 }`

## Rollout
1. Enable in one environment/domain.
2. Create/Update Shipments/Orders to trigger edge materialization.
3. Add CSR rule and verify list behavior.

## Future work
- Backfill job for historical data (scan Orders/Shipments; re-infer edges).
- Metrics (edge counts, reason time, rewrite hits).
- SHACL-lite validator integration for write-time constraints.
