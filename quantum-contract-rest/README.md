# Quantum Contract REST

Opt-in JAX-RS adapter for the generated-SDK contract identity handshake.

The module exposes `GET /contract-hash` without pulling in
`quantum-framework`. Services with a generated OpenAPI SDK select this adapter
and configure `quantum.contract.openapi-location`. Code that only computes or
compares contract identities should depend on `quantum-contract-core`.
