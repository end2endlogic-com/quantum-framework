# Bootstrap Pack Design

## 1. Purpose

This design introduces a framework-level mechanism for replayable bootstrap
defaults.

The goal is to fill the gap between:
- database migrations
- seed packs
- imperative application bootstrap services

This mechanism is intended for cases such as:
- reapplying missing demo defaults
- provisioning environment-sensitive baseline resources
- ensuring app-owned integrations are present
- publishing workflow-platform demo assets from a product application
- validating or repairing reconstructable startup defaults

## 2. Problem Statement

The Quantum framework already has two mature replay models:
- migrations for persisted schema/data evolution
- seed packs for deterministic baseline datasets

Applications still have a third category of startup/default work that does not
fit either model well.

Examples:
- ensure a startup identity or connector binding exists
- ensure a tenant filesystem or managed directory exists
- ensure shared-storage configuration is wired correctly
- publish a workflow definition/version/deployment if absent
- register or validate a workflow-platform source binding
- provision a demo exchange stack based on current environment settings

Today that work is usually implemented as idempotent application service code.
It can be safe and repeatable, but it is not governed by a framework-level
replay primitive.

That creates three issues:
- operators do not have one standard way to reapply missing defaults
- applications blur "seed data" and "bootstrap provisioning" in ad hoc code
- cross-product defaults such as workflow-platform demo setup cannot be replayed
  the way seeds and migrations can

## 3. Decision

Add a framework-level `BootstrapPack` mechanism in Quantum.

This mechanism should:
- be owned by the Quantum framework runtime/startup layer
- record scoped pack runs and per-step outcomes
- default to safe `APPLY_MISSING` behavior
- allow products to contribute application-owned bootstrap packs
- support both startup auto-apply and explicit admin-triggered reapply

Applications own the content of their packs.
The framework owns the replay primitive.

Initial slice note:
- the first implementation lands in
  `/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/bootstrap`
- if the contracts need broader cross-module reuse later, they can be extracted
  into a dedicated module after the SPI stabilizes

## 4. Positioning

### 4.1 Migrations

Use migrations when the system must evolve persisted structures or canonical
records in a versioned way.

Typical examples:
- collection/index changes
- backfills
- required structural corrections

### 4.2 Seed packs

Use seed packs when the desired input is deterministic data packaged as a
dataset.

Typical examples:
- reference ontologies
- code lists
- baseline model records
- stable demo lookup data

### 4.3 Bootstrap packs

Use bootstrap packs when the work is operational, idempotent, and may require
service orchestration or environment-derived decisions.

Typical examples:
- ensure a filesystem exists
- ensure app-owned settings/secrets bindings are complete
- ensure a workflow-platform demo publication exists
- ensure a product-specific demo route or binding exists
- repair missing defaults that are reconstructable from current scope

## 5. Why This Belongs In The Framework

This is a startup/replay concern, not a workflow-platform-only concern.

That is visible in the current framework shape already:
- migrations are coordinated centrally by
  [FrameworkStartupCoordinator.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/service/startup/FrameworkStartupCoordinator.java)
- applications such as B2BI currently layer their own bootstrap behavior on top
  of the startup path

`BootstrapPack` belongs alongside seed and migration infrastructure because it
answers the same operational question:

"How do I safely make sure required defaults exist for this scope?"

What remains application-specific is:
- which defaults should exist
- how to detect a missing asset
- how to create or repair that asset safely

## 6. Design Principles

- Keep migrations, seed packs, and bootstrap packs distinct.
- Default to `APPLY_MISSING`, not overwrite.
- Persist run history for auditability and operator support.
- Make scope explicit.
- Keep pack execution idempotent.
- Let applications own provisioning logic.
- Let shared consumers such as `workflow-platform` use framework-level replay
  rather than inventing a parallel bootstrap primitive.
- Treat bootstrap-pack invocation as the authorization boundary; after a pack
  is authorized to run, internal step execution may use framework-owned
  ignore-rules mode so app provisioning logic can perform its repo writes
  consistently at startup and through admin reapply flows.

## 7. Scope

### 7.1 In scope

- Framework-level bootstrap-pack contracts
- Framework-level runner and history model
- Startup auto-apply hooks
- Admin/apply and validate API conventions
- Application-contributed pack registrations
- Consumer use cases such as workflow-platform demo/default publication

### 7.2 Out of scope

- Replacing migrations
- Replacing seed packs
- Arbitrary destructive reconciliation
- Turning all app bootstrap logic into seed datasets

## 8. Core Model

### 8.1 Pack identity

A bootstrap pack is identified by:
- `packRef`
- `packVersion`
- `productRef`

Optional qualifiers:
- `environmentRef`
- `profileRef`
- `realmRef`
- `tenantRef`

Example:

```json
{
  "packRef": "b2bi-local-demo-defaults",
  "packVersion": "2026.04.0",
  "productRef": "b2bi",
  "profileRef": "local-demo"
}
```

### 8.2 Execution modes

A pack should support explicit apply semantics:
- `APPLY_MISSING`
- `REAPPLY`
- `VALIDATE_ONLY`

Meaning:
- `APPLY_MISSING`
  Create or repair only missing/defaultable assets.
- `REAPPLY`
  Re-run idempotent steps even if assets already exist.
- `VALIDATE_ONLY`
  Report presence/drift without mutation.

Default mode should be `APPLY_MISSING`.

### 8.3 Step model

A bootstrap pack is a sequence of named idempotent steps.

Each step has:
- `stepRef`
- `description`
- `kind`
- `applyPolicy`
- `dependsOn`

V1 step kinds:
- `SEED_PACK`
- `APP_SERVICE`
- `HTTP_CALL`
- `ASSERTION`
- `WORKFLOW_PLATFORM_PUBLICATION`

V1 apply policies:
- `ONCE_PER_SCOPE`
- `WHEN_MISSING`
- `ALWAYS_IDEMPOTENT`

Example:

```json
{
  "packRef": "b2bi-local-demo-defaults",
  "steps": [
    {
      "stepRef": "ensure-shared-storage",
      "kind": "APP_SERVICE",
      "applyPolicy": "WHEN_MISSING"
    },
    {
      "stepRef": "publish-workflow-platform-demo",
      "kind": "WORKFLOW_PLATFORM_PUBLICATION",
      "applyPolicy": "WHEN_MISSING",
      "dependsOn": ["ensure-shared-storage"]
    },
    {
      "stepRef": "validate-demo-route",
      "kind": "ASSERTION",
      "applyPolicy": "ALWAYS_IDEMPOTENT",
      "dependsOn": ["publish-workflow-platform-demo"]
    }
  ]
}
```

## 9. State and Audit Model

Bootstrap packs need persisted execution history, analogous in spirit to
migration changesets and seed applications, but recorded as scoped pack runs.

Suggested record shape:

```json
{
  "packRunRef": "bpr-123",
  "packRef": "b2bi-local-demo-defaults",
  "packVersion": "2026.04.0",
  "mode": "APPLY_MISSING",
  "scope": {
    "productRef": "b2bi",
    "environmentRef": "local",
    "realmRef": "b2bi-com",
    "tenantRef": "b2bi.com"
  },
  "status": "COMPLETED",
  "steps": [
    {
      "stepRef": "ensure-shared-storage",
      "status": "COMPLETED",
      "outcome": "CREATED"
    },
    {
      "stepRef": "publish-workflow-platform-demo",
      "status": "COMPLETED",
      "outcome": "ALREADY_PRESENT"
    }
  ]
}
```

This is intentionally different from:
- migration tracking by applied changeset
- seed tracking by applied seed pack/dataset

Bootstrap packs should preserve operator-visible step outcomes because their
job is often to repair or validate operational defaults.

## 10. Scope Model

Bootstrap defaults are scope-sensitive.

The V1 scope model should support:
- `productRef`
- `environmentRef`
- `realmRef`
- `tenantRef`
- `workspaceRef`

Consumers such as workflow-platform may also project:
- `workflowDefinitionRef`
- `deploymentRef`
- `adapterRef`

The framework should not hardcode those consumer-specific keys, but it should
allow packs to carry structured scope/config payloads that downstream step
handlers can interpret.

## 11. Framework vs Application Split

### 11.1 Framework-owned

The framework should own:
- `BootstrapPack` contracts
- runner service
- run-history persistence
- startup auto-apply rules
- apply/validate endpoint patterns
- CDI registration/discovery model

### 11.2 Application-owned

Applications should own:
- concrete pack definitions
- step handlers
- missing-default detection logic
- safe repair logic

### 11.3 Consumer-owned helpers

Shared consumers such as `workflow-platform` may contribute helper step kinds
or utilities, but they should not own the replay primitive itself.

Examples:
- publish workflow definition/version/deployment
- register adapter bundle
- validate source-binding presence
- run smoke assertions against a demo path

## 12. Proposed API and SPI

### 12.1 Registration model

Products register packs explicitly, much like seed sources or adapter bundles.

Suggested contracts:

```java
public interface BootstrapPackContributor {
    Collection<BootstrapPackDefinition> bootstrapPacks();
}

public interface BootstrapPackStepHandler {
    boolean supports(String kind);
    BootstrapStepResult execute(BootstrapStepRequest request);
}
```

### 12.2 Apply request

```text
ApplyBootstrapPackRequest {
  packRef
  mode
  productRef
  environmentRef
  realmRef
  tenantRef
  workspaceRef
  actorRef
}
```

### 12.3 Step result

```text
BootstrapStepResult {
  status
  outcome
  details
}
```

Expected outcomes:
- `CREATED`
- `UPDATED`
- `ALREADY_PRESENT`
- `SKIPPED`
- `FAILED`

## 13. Startup Integration

The pack runner should extend the existing startup story, not replace it.

Current startup coordination already runs:
1. migrations
2. baseline identity startup
3. seed startup
4. pending seed logging

See
[FrameworkStartupCoordinator.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/service/startup/FrameworkStartupCoordinator.java).

Suggested ordering with bootstrap packs:
1. migrations
2. baseline identity startup
3. seed startup
4. bootstrap pack startup apply
5. pending seed / pending bootstrap logging

That order keeps:
- schema ready first
- foundational identities available
- deterministic data present
- environment-sensitive bootstrap last

## 14. Operational Surfaces

V1 should expose three ways to run packs:
- startup auto-apply for selected profiles/scopes
- admin endpoint to apply one named pack
- validate endpoint for drift detection

Suggested endpoint family:
- `POST /system/bootstrap/packs/{packRef}/apply`
- `POST /system/bootstrap/packs/{packRef}/validate`
- `GET /system/bootstrap/packs`
- `GET /system/bootstrap/runs`

Application-specific admin resources may wrap these in V1 if needed.

## 15. First Consumer: Workflow Platform

`workflow-platform` should consume this framework primitive rather than owning a
parallel bootstrap mechanism.

For workflow-platform, bootstrap packs are useful for:
- ensuring adapter demo bundles are registered
- publishing demo definition/version/deployment artifacts
- validating source bindings
- ensuring end-to-end smoke prerequisites are available

This keeps workflow-platform focused on orchestration semantics while relying on
Quantum for operational replay of application defaults.

## 16. First Adopter: B2BI

The first concrete adopter should be B2BI.

Suggested pack:
- `packRef`: `b2bi-local-demo-defaults`
- `productRef`: `b2bi`
- `profileRef`: `local-demo`

Suggested steps:
1. Ensure shared-storage settings/secrets/filesystem defaults exist.
2. Ensure B2BI workflow-connector resolver defaults exist.
3. Ensure workflow-platform publication defaults exist for the B2BI shared
   storage demo path.
4. Ensure the B2BI source binding defaults exist.
5. Validate the live write-plus-signal demo path.

Important boundary:
- legacy seed packs can continue to own embedded-runtime demo data
- bootstrap packs should own workflow-platform-oriented demo defaults and other
  operationally reconstructed assets

## 17. Risks

- Letting bootstrap packs become a second migration system
- Hiding destructive reconciliation behind innocent "reapply" semantics
- Moving too much app-specific provisioning into framework code
- Allowing consumer-specific keys to pollute the core contracts

## 18. Recommendation

Introduce `BootstrapPack` as a Quantum framework primitive.

That gives us:
- a clean answer to "reapply missing defaults"
- one replay model shared by all Quantum applications
- a better home for workflow-platform demo/default provisioning
- a path away from ad hoc product-specific bootstrap endpoints without forcing
  everything into seed packs
