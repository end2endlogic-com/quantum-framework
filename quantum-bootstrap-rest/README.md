# Quantum Bootstrap REST

`quantum-bootstrap-rest` is the opt-in system control-plane adapter for
bootstrap-pack discovery, validation, application, and run history. It owns
the existing `/admin/bootstrap-packs` endpoint family.

Only the system-management service should select this artifact. Applications
that merely contribute bootstrap packs should depend on
`quantum-bootstrap-core`, which contains no JAX-RS resources.

The Java package and HTTP paths are unchanged from their former location in
`quantum-framework`.

## Service-only application initialization

Opt in at build time with `quantum.tenant-initialization.enabled=true`. The
independent `/internal/tenant-initializations` surface remains available when
`quantum.system-rest.enabled=false`; no platform admin routes are enabled.
Configure `quantum.tenant-initialization.application-id`, `environment-ref`, and
`caller-application-id`, a remote central `SystemDirectory`, an application-owned
`quantum.bootstrap.run-store-realm` with majority Mongo acknowledgement, and the
actual complete runtime bundle SHA256 in
`quantum.provisioning.runtime-artifact-sha256`.

POST requires an issuer-verified service token with the `tenant-initializer` role,
no resource audience (the existing service-passport contract), and the configured caller application in
`azp`. Central policy must also admit this role for `SYSTEM/TENANT_INITIALIZATION`
metadata access; minting the role alone does not grant permission. Send `X-Realm`
matching the request's `realmRef`. The request pins execution key, application/environment, central
single-tenant realm/domain, bootstrap pack version/content digest, and runtime
digest. It never creates catalog, identity, user or grant records. Pooled tenant
initialization is explicitly unsupported by this boundary.

Declare typed `BootstrapPackDefinition` lists through existing CDI contributors
or explicit JSON classpath resources in `quantum.bootstrap.pack-resources`.
`MIGRATIONS` steps have empty declared config; `SEED_PACK` steps declare
`seedPacks: [{name, version}]` with exact semantic versions. The shared handler
resolves every migration/seed plan before any effect and persists the prepared
pack with dataset/manifest and migration fingerprints. Execution revalidates
these stored plans and uses the shared transactional seed/migration services.
No latest-version selection occurs during execution.

A duplicate key observes its stored COMPLETED, FAILED or RUNNING receipt;
changing its request returns a conflict. RUNNING returns HTTP202, terminal
receipts HTTP200 with explicit status/step outcomes. FAILED is never readiness.
Observation works after catalog/runtime changes but does not resume execution.
A lost checkpoint remains RUNNING and requires reviewed recovery; there is no
automatic retry, recovery loop or lease takeover. Generate consumers from the
producer's captured OpenAPI rather than copying request or receipt DTOs.

The template disables issuer signing and startup migrations in its remote profile.
Its signature/issuer verifier accepts the existing audience-free service passport;
shared SecurityFilter retains strict target-audience checks for user tokens.

`helix-sdk.yml` generates Java/TypeScript clients from
`contracts/quantum-tenant-initialization.openapi.json`. Refresh that slice with
`scripts/capture-initialization-contract.py <producer-openapi.json> <output.json>`:
it follows producer schema references and records the source digest, rather than
transcribing DTOs. The current captured operation was built and exercised on the
local template runtime; consumers/artifacts still require the normal versioned
publication process before rollout. System Manager currently retains its older
DI callback and has not switched to this client.
