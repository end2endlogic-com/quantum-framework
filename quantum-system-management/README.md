# Quantum System Management

`quantum-system-management` contains endpoint-free implementations and API
payloads used by the shared system control plane, including authentication,
tenant provisioning, onboarding, application admission, policy-file loading,
and shared REST DTO/service packages.

`quantum-system-rest` adds the server-facing JAX-RS resources on top of this
module. Applications that call the control plane through generated SDKs do not
need either server artifact.

This artifact does not expose REST endpoints.

## Exact tenant initialization

The tenant provisioning run persists a versioned initialization plan before
catalog or identity writes. Dedicated realms pin ordered migrations, base seed
packs and requested archetypes. Pooled tenant admission does not bootstrap its
existing realm. A failed run's explicit retry loads the stored plan, restores
completed step results, and validates it before claiming the run. It never
resolves latest artifacts again.

Set `quantum.provisioning.runtime-artifact-sha256` to the immutable deployed
runtime artifact's SHA-256 digest. Missing or changed runtime identity blocks
new initialization or recovery. Release tooling must supply the actual digest;
this setting is a deployment declaration, not runtime attestation. Migration
behavior affected by configuration or external assets must include those
inputs in the migration's declared checksum and release contract.

Migration execution validates the exact catalog under the existing realm lock.
Each migration and its digest-bearing completion record share a MongoDB
transaction. Changesets must perform database writes using the supplied
`MorphiaSession`; separate clients and external effects are outside that
transaction. Legacy completion records without definition hashes and legacy
runs without plans require reviewed recovery. They are not silently trusted or
replayed. Planning requires readable Java implementation class resources;
unsupported native deployments fail explicitly.

Seed plans preflight manifest and dataset hashes and scope before writing,
then reuse existing seed execution and receipts. This does not add atomic
seed-write/receipt commits or recover abandoned RUNNING runs. Application
callbacks and their separate runtime still require an authoritative durable
initialization contract before automatic installation recovery is safe.

Executable workflows reject malformed definitions and unsafe step order.
Display-only parsing retains compatibility with historical records.
