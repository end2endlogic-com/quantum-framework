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

`VectorConformanceTest` and `CanonicalTBoxHasherTest` run against these files.
When the canonical semantics change, update the vector and the golden hash here.
