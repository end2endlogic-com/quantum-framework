# Scoped Action Enablement Design

## 1. Purpose

This design introduces a generic framework-level way to answer whether a scoped action is:

- allowed by policy,
- enabled by feature flags,
- operationally ready based on configuration and runtime dependencies.

The design is intentionally built around the existing Quantum security primitives:

- `FunctionalArea`
- `FunctionalDomain`
- `Action`

The irreducible unit is therefore a scoped action:

`area / functionalDomain / action`

This avoids introducing a parallel authorization abstraction that would drift away from the framework's existing policy model.

## 2. Problem Statement

Today the framework can already answer:

- "Is this identity allowed to perform `area/domain/action`?"
- "Is this feature flag enabled?"

Applications still need a second layer to answer:

- "Is this scoped action actually usable right now?"

Examples:

- A user may be allowed to create an integration, but shared storage is not provisioned.
- A user may be allowed to receive a file, but the required secrets are missing.
- A feature may be enabled, but the backing service is unreachable.

This gap currently pushes application teams to implement readiness logic ad hoc in UI code and page-local service calls.

## 3. Decision

Add a generic scoped-action enablement layer to the open-source framework.

This layer will:

- evaluate readiness using the same `area/domain/action` tuple the framework already uses for permission checks,
- compose policy, feature flags, settings, resource existence, runtime reachability, and ontology-backed dependencies,
- expose a generic API that applications and shells can query.

Enterprise modules may contribute additional dependency resolvers, but the core engine and contracts belong in the open-source framework.

## 4. Design Principles

### 4.1 Keep policy canonical

The canonical authorization primitive remains the scoped action tuple represented today by the same conceptual shape as `SecurityURIHeader`.

The new layer must not replace:

- `PermissionResource`
- `SecurityCheckResponse`
- ontology-aware policy evaluation

It should compose them.

### 4.2 Separate three questions

The framework needs distinct answers for distinct concerns:

- `allowed?`
  Policy and role/rule evaluation.
- `enabled?`
  Feature flag and administrative rollout state.
- `ready?`
  Configuration, resources, and runtime operational dependencies.

The UI should generally enable an action only when:

`allowed && enabled && ready`

### 4.3 Keep "capability" as a composition concept

If applications want to talk about "capabilities", those should be derived aggregates over multiple scoped actions.

A capability is useful for:

- menus,
- dashboards,
- setup summaries,
- shell payloads.

It should not become a new core security primitive.

## 5. Scope

## 5.1 In scope

- Generic framework contracts for scoped-action enablement.
- Generic enablement evaluator and resolver SPI.
- Generic REST endpoint for checking one or more scoped actions.
- Ontology-ready relationship model for action dependencies.
- Pluggable enterprise resolvers.

## 5.2 Out of scope

- Replacing policy rules with capability rules.
- Embedding enterprise concepts like tenant filesystem mounts into OSS contracts.
- Building one application-specific dashboard.

## 6. Core Concepts

### 6.1 Scoped Action

The atomic unit of evaluation.

```text
ScopedActionRef {
  area: String
  functionalDomain: String
  action: String
}
```

Examples:

- `integration / exchange / create`
- `settings / secrets / view`
- `settings / secrets / save`
- `b2bi / inbound-receipt / create`

### 6.2 Scoped Action Requirement

A definition of what must be true for a scoped action to be operationally available.

It may require:

- permission check,
- feature flag(s),
- configuration key(s),
- resource existence,
- runtime service reachability,
- ontology relationship(s),
- another scoped action to be enabled.

### 6.3 Dependency Check

A single evaluable prerequisite with a typed source.

Examples:

- `permission`
- `feature-flag`
- `setting-present`
- `resource-exists`
- `service-reachable`
- `ontology-relation`
- `scoped-action-enabled`

### 6.4 Enablement Status

The response for one scoped action.

```text
allowed: boolean
enabled: boolean
ready: boolean
state: ALLOWED_ENABLED_READY | BLOCKED
blockers: List<EnablementBlocker>
```

Each blocker should be machine-readable and user-displayable.

## 7. Framework vs Enterprise Split

## 7.1 Open-source framework responsibilities

The framework should own:

- scoped-action contracts,
- generic dependency SPI,
- generic evaluator service,
- permission and feature-flag resolvers,
- generic config and entity-exists resolvers,
- generic REST endpoint,
- optional ontology-backed dependency metadata.

## 7.2 Enterprise responsibilities

Enterprise modules should contribute resolvers for enterprise infrastructure, for example:

- managed secrets configured,
- tenant filesystem provisioned,
- standard mounts present,
- workflow runtime reachable,
- transform engine available,
- EDI runtime available.

This keeps OSS generic while still allowing enterprise applications to describe real operational blockers.

## 8. Proposed Module Layout

Under `framework/`:

- `quantum-action-enablement-models`
- `quantum-action-enablement-quarkus`

Optional enterprise add-on under `quantum-enterprise/`:

- `quantum-action-enablement-enterprise`

Alternative enterprise splitting is acceptable if we later want smaller provider modules by domain, but the generic engine should still live in OSS.

## 9. API Shape

The API should operate on one or more scoped actions.

Suggested endpoint:

- `POST /system/actions/enablement/check`

Suggested request:

```json
{
  "identity": "tenant-admin",
  "realm": "acme",
  "actions": [
    {
      "area": "integration",
      "functionalDomain": "exchange",
      "action": "create"
    },
    {
      "area": "settings",
      "functionalDomain": "secrets",
      "action": "save"
    }
  ]
}
```

Suggested response shape:

```json
{
  "results": [
    {
      "scopedAction": {
        "area": "integration",
        "functionalDomain": "exchange",
        "action": "create"
      },
      "allowed": true,
      "enabled": true,
      "ready": false,
      "blockers": [
        {
          "type": "resource-exists",
          "code": "tenant-filesystem-missing",
          "message": "No tenant managed filesystem is provisioned."
        }
      ]
    }
  ]
}
```

## 10. Ontology Usage

Ontology should be used to model relationships between scoped actions and prerequisites, not to replace permission evaluation.

Useful relationships include:

- `application exposes action`
- `scoped action depends on scoped action`
- `scoped action requires feature flag`
- `scoped action requires resource`
- `scoped action requires service`

Ontology usage should remain additive. The first implementation may use a registry-backed manifest and add ontology-backed persistence later.

## 11. Initial Application Targets

The first consumer should be B2BI because it has visible operational setup dependencies and currently duplicates readiness checks in the UI.

Initial B2BI scoped actions:

- `integration / exchange / create`
- `integration / guided-integration / create`
- `b2bi / durable-ingress / create`
- `workflow / execution / execute`
- `settings / secrets / save`

## 12. Rollout Strategy

### Phase 1

- Add OSS contracts and evaluator SPI.
- Add permission and feature-flag resolvers.
- Add config/entity/service resolver base types.

### Phase 2

- Add enterprise resolvers for secrets and filesystem dependencies.
- Add a B2BI action manifest.

### Phase 3

- Update B2BI Studio to query enablement API and disable actions with clear blocker messaging.

### Phase 4

- Add shell and dashboard views for setup progress and action readiness.

## 13. Success Criteria

The design is successful when:

- applications no longer hand-roll readiness checks for major actions,
- permission and setup concerns remain separate but composable,
- the UI can explain exactly why a scoped action is blocked,
- enterprise infrastructure dependencies can be plugged in without contaminating OSS contracts.
