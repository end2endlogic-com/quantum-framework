# Quantum Seed Core

`quantum-seed-core` contains the endpoint-free seed-pack runtime: discovery,
validation, loading, transformation, persistence, startup, and health
infrastructure.

Use this module when a service needs to load or manage seed data without
pulling in `quantum-framework` or any JAX-RS resources. Optional seed-source
adapters, such as `quantum-seed-s3`, depend on this module.

This artifact does not expose REST endpoints.
