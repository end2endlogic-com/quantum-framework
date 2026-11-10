# TBox conformance vectors

Canonical conformance vectors for Quantum's ontology semantics and the stable
TBox hash. **Owned by this repository** — they are the reference the Java
implementation must satisfy; they are not synced from any external source.

Each vector pins one aspect:

- `transitive.json`, `symmetric.json`, `inverse.json`, `subpropertyof.json` —
  property-characteristic inference.
- `typing-precedence.json` — observed node type wins over declared domain/range.
- `closed-rejection.json` — closed-world rejection.
- `chain.json`, `combined.json` — multi-rule interaction.
- `hash-golden.json` — the canonical stable TBox hash (per the HASH_SPEC).
- `hash-golden-stability.json` — the relation contract fields in the hash (`stability` is hashed
  only when `stable`; `derivation` is the `inferred` bit).
- `stable-derived-explicit-rejection.json` — explicit edges for a stable derived relation are
  rejected (`rejected_derived_predicates`).
- `stable-relation-diff.json` — the stable-relation admission diff (`StableRelationDiffTest`).

The three relation-contract vectors are copied byte-for-byte from the Python ontology repo, which
runs the same files; do not edit them here.

`VectorConformanceTest`, `CanonicalTBoxHasherTest`, `StableRelationContractTest` and
`StableRelationDiffTest` run against these files.
When the canonical semantics change, update the vector and the golden hash here.
