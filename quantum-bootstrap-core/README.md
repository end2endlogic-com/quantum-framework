# Quantum Bootstrap Core

`quantum-bootstrap-core` contains the endpoint-free bootstrap-pack models,
contributor and handler SPI, execution runtime, run history, and startup
infrastructure.

Use this module when an application or product contributes bootstrap packs or
step handlers. It does not expose REST endpoints and does not depend on the
historical `quantum-framework` bundle.

## Durable execution history

The CDI run repository stores `BootstrapPackRun` through the shared governed
Morphia repository. Configure `quantum.bootstrap.run-store-realm` explicitly to
an application-owned metadata realm. No store setting means operations fail
before handlers execute; the engine never falls back to process memory.
`InMemoryBootstrapPackRunRepository` remains an explicit embedded/test fixture.
Custom run repositories must implement atomic `createIfAbsent`; the default
SPI method rejects unsupported adapters before effects rather than emulating
atomic creation with a read and write.

A run is persisted as RUNNING before the first handler, then checkpointed after
every step. Storage failure after a handler may leave RUNNING with an uncertain
step outcome. This is observable history, not permission to repeat the handler.
Handlers must return explicit status and outcome; missing results are recorded
as failure rather than defaulted to successful completion.
Mongo's unique execution-key index arbitrates initial requests. The adapter
requires majority write acknowledgement before creation; configure the shared
Mongo client with `quarkus.mongodb.write-concern.w=majority`. Weaker
acknowledgement fails before handlers run.

`apply(request, executionRef)` requires durable storage and
`quantum.provisioning.runtime-artifact-sha256` with the deployed runtime's real
SHA-256. It stores the request fingerprint and serialized pack definition.
Identical keys observe the original RUNNING, FAILED or COMPLETED run even after
restart or catalog changes. Changed requests conflict; neither failures nor
interrupted runs are automatically replayed. Calls without a key still create
distinct runs and are not idempotent across separate submissions.

The pack definition records declared configuration, not resolved external seed
or migration artifacts. Safe recovery still requires handler-owned exact plans
and authoritative effect receipts, plus proof of exclusive execution. The
application callback and System Manager have not yet adopted this keyed API.
Do not enable automatic callback retries on the strength of these checkpoints.

Bootstrap REST history now includes RUNNING and optional request fingerprint,
pack-definition JSON and runtime digest fields. A matching producer OpenAPI and
regenerated SDK release is required for consumers of that REST surface before
rollout; this change does not publish those artifacts.
