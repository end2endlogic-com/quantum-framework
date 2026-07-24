# Quantum Contract Core

`quantum-contract-core` contains endpoint-free OpenAPI contract hashing and
generated-SDK compatibility checks.

Service hosts use it to publish canonical contract identities. Generated SDK
providers use it to reject incompatible services without pulling in
`quantum-framework`, persistence repositories, or REST endpoint modules.

This artifact does not expose REST endpoints.
