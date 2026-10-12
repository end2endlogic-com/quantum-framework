# Quantum Seed Core

`quantum-seed-core` contains the endpoint-free seed-pack runtime: discovery,
validation, loading, transformation, persistence, startup, and health
infrastructure.

Use this module when a service needs to load or manage seed data without
pulling in `quantum-framework` or any JAX-RS resources. Optional seed-source
adapters, such as `quantum-seed-s3`, depend on this module.

This artifact does not expose REST endpoints.

## Exact seed plans

Use `SeedLoaderService.planSeeds(context, refs)` or
`planArchetype(context, name)` to resolve an ordered dependency closure without
writing seed data. Persist the returned `SeedPlan` on the owning operation
before calling `applyPlan(context, plan)`.

The plan contains exact pack versions, manifest and dataset SHA-256 hashes,
and a fingerprint of the realm, tenant, organization, account, owner and
substitution variables. Execution validates the entire plan before its first
write and uses the parsed bytes from that validation. Adding a newer dependency
version does not change an existing plan. Missing or ambiguous packs, altered
manifests/data, invalid dependencies and changed scope fail explicitly.
Manifest hashing preserves ordered compound-index keys; it deliberately treats
serialization changes as drift. Pin the runtime build as well: hashes of seed
assets do not identify Java transforms, listeners or variable resolvers.

Execution uses the existing repository and seed registry with conflict policy
`ERROR`. A matching dataset receipt suppresses a repeated write. The caller
still owns authorization, durable plan storage, exclusive execution and
reconciliation of interrupted writes. In particular, a crash between a dataset
write and its receipt is not an exactly-once guarantee; resume requires the
repository's transaction/idempotency contract. This API neither schedules
retries nor pins migrations. It holds all selected parsed datasets in memory
until preflight finishes, so callers must bound admitted seed-pack sizes.
