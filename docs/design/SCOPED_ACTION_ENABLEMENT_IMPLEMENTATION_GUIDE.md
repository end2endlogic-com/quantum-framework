# Scoped Action Enablement Implementation Guide

## 1. Purpose

This guide turns the scoped-action enablement design into an executable implementation plan for the Quantum framework and its first B2BI pilot.

Primary references:

- [SCOPED_ACTION_ENABLEMENT_DESIGN.md](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/docs/design/SCOPED_ACTION_ENABLEMENT_DESIGN.md)
- [PermissionResource.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/rest/resources/PermissionResource.java)
- [SecurityCheckResponse.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-models/src/main/java/com/e2eq/framework/model/securityrules/SecurityCheckResponse.java)
- [FeatureFlag.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-models/src/main/java/com/e2eq/framework/model/general/FeatureFlag.java)

## 2. Module Plan

## 2.1 Open-source framework modules

Add the following modules under `framework/` and wire them into [framework/pom.xml](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/pom.xml):

- `quantum-action-enablement-models`
- `quantum-action-enablement-quarkus`

## 2.2 Enterprise module

Add one enterprise add-on under `quantum-enterprise/` and wire it into [quantum-enterprise/pom.xml](/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise/pom.xml):

- `quantum-action-enablement-enterprise`

This provider jar should depend on the OSS `quarkus` module and contribute enterprise-specific resolvers.

## 3. OSS Models Module

Create `framework/quantum-action-enablement-models`.

Recommended package:

- `com.e2eq.framework.actionenablement`

Create these initial classes:

- `ScopedActionRef`
- `ScopedActionRequirement`
- `DependencyCheckRef`
- `EnablementBlocker`
- `ScopedActionEnablementStatus`
- `ScopedActionEnablementRequest`
- `ScopedActionEnablementResponse`

Suggested shape:

### `ScopedActionRef`

Fields:

- `area`
- `functionalDomain`
- `action`

Notes:

- normalize to lower case, matching the behavior of [SecurityURIHeader.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-models/src/main/java/com/e2eq/framework/model/securityrules/SecurityURIHeader.java)
- add a `toUriString()` helper like `area/functionalDomain/action`

### `DependencyCheckRef`

Fields:

- `type`
- `refName`
- `config`

`type` is one of:

- `permission`
- `feature-flag`
- `setting-present`
- `entity-exists`
- `service-reachable`
- `scoped-action`
- `ontology-relation`

### `EnablementBlocker`

Fields:

- `type`
- `code`
- `message`
- `severity`
- `metadata`

This is the user-facing and machine-readable explanation of why an action is blocked.

### `ScopedActionRequirement`

Fields:

- `scopedAction`
- `dependencies`
- optional `displayName`
- optional `description`

This is the registry or manifest definition.

### `ScopedActionEnablementStatus`

Fields:

- `scopedAction`
- `allowed`
- `enabled`
- `ready`
- `blockers`

Derived helper:

- `isUsable() => allowed && enabled && ready`

## 4. OSS Quarkus Module

Create `framework/quantum-action-enablement-quarkus`.

Recommended responsibilities:

- CDI registry for requirements
- resolver SPI
- evaluator service
- REST resource

Recommended packages:

- `com.e2eq.framework.actionenablement.runtime`
- `com.e2eq.framework.actionenablement.rest`
- `com.e2eq.framework.actionenablement.spi`

## 4.1 Resolver SPI

Create:

- `ActionDependencyResolver`

Suggested interface:

```java
public interface ActionDependencyResolver {
    String supportsType();
    DependencyResolutionResult evaluate(
        DependencyCheckRef dependency,
        PrincipalContext principalContext,
        DataDomain dataDomain
    );
}
```

Create:

- `DependencyResolutionResult`

Fields:

- `satisfied`
- `blocker`

## 4.2 Requirement Registry SPI

Create:

- `ScopedActionRequirementRegistry`

Suggested methods:

- `Optional<ScopedActionRequirement> get(ScopedActionRef ref)`
- `List<ScopedActionRequirement> list()`

Start with an in-memory CDI-backed registry so applications can contribute manifests with beans.

## 4.3 Evaluator Service

Create:

- `ScopedActionEnablementService`

Responsibilities:

- resolve the manifest entry for each scoped action,
- run permission resolver first,
- run feature-flag resolver next,
- run remaining resolvers,
- collect blockers,
- derive `allowed`, `enabled`, and `ready`.

Rules:

- `allowed` should be driven by the policy check result.
- `enabled` should be driven by feature flags and any explicit administrative enablement dependencies.
- `ready` should be driven by non-policy operational dependencies.
- missing manifest entry should produce a clear blocker instead of silently allowing.

## 4.4 REST Resource

Create:

- `ActionEnablementResource`

Suggested path:

- `@Path("/system/actions/enablement")`

Suggested operations:

- `POST /check`
- `GET /manifest`

`POST /check` is the main runtime endpoint for UI and shell queries.

## 5. Core OSS Resolvers

Implement these in the OSS `quarkus` module first.

### 5.1 Permission Resolver

Dependency type:

- `permission`

Behavior:

- construct a `SecurityURIHeader`-equivalent request using the target `ScopedActionRef`
- evaluate using the existing policy stack through the same underlying rule context used by [PermissionResource.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/rest/resources/PermissionResource.java)

Blocker example:

- code: `permission-denied`

### 5.2 Feature Flag Resolver

Dependency type:

- `feature-flag`

Behavior:

- resolve a feature flag by ref name
- block when missing or disabled

Blocker examples:

- `feature-flag-missing`
- `feature-flag-disabled`

### 5.3 Config Property Resolver

Dependency type:

- `setting-present`

Behavior:

- check named configuration or registry-backed setting presence
- keep this generic; do not encode app-specific settings here

### 5.4 Entity Exists Resolver

Dependency type:

- `entity-exists`

Behavior:

- generic existence check for known model refs
- may need repository lookup metadata in dependency config

## 6. Enterprise Resolver Module

Create `quantum-enterprise/quantum-action-enablement-enterprise`.

This module contributes resolvers for enterprise runtime dependencies only.

Initial resolver candidates:

- `managed-secret-configured`
- `tenant-filesystem-provisioned`
- `standard-mounts-present`
- `workflow-runtime-reachable`
- `camel-runtime-reachable`

These should return precise blockers such as:

- `tenant-filesystem-missing`
- `filesystem-standard-mounts-missing`
- `managed-secret-missing`
- `managed-secret-not-configured`
- `service-unreachable`

## 7. B2BI Pilot Manifest

The first application pilot should live in `b2bi-server`.

Create an application contributor bean that registers requirement definitions for initial B2BI scoped actions.

Initial actions:

- `integration / exchange / create`
- `integration / guided_integration / create`
- `integration / workflow / create`
- `integration / inboundreceipt / create`

Example requirement:

```text
integration / guided_integration / create
  - permission
  - feature flag: guided-integrations
  - tenant-filesystem-provisioned
  - standard-mounts-present
  - managed-secret-configured: b2bi-s3-access-key
  - managed-secret-configured: b2bi-s3-secret-key
```

## 8. UI Rollout

Once the API is live, B2BI Studio should stop doing page-local setup heuristics for critical action gating.

Initial rollout targets:

- dashboard setup cards
- `Generate Integration`
- `Create Exchange`
- `Receive File`
- `Execute`

UI behavior:

- disable the action when `usable == false`
- show top blocker message inline
- optionally show all blockers in a message panel or tooltip
- provide a direct `Open Settings` or context-specific navigation action

## 9. Suggested File Layout

OSS framework:

- `framework/quantum-action-enablement-models/pom.xml`
- `framework/quantum-action-enablement-models/src/main/java/...`
- `framework/quantum-action-enablement-quarkus/pom.xml`
- `framework/quantum-action-enablement-quarkus/src/main/java/...`

Enterprise:

- `quantum-enterprise/quantum-action-enablement-enterprise/pom.xml`
- `quantum-enterprise/quantum-action-enablement-enterprise/src/main/java/...`

Pilot application:

- `b2bi-server/src/main/java/.../B2biActionEnablementManifest.java`

## 10. First Slice

The first implementation slice should be intentionally narrow:

1. create the two OSS modules
2. add the model contracts
3. add the registry SPI
4. add the permission and feature-flag resolvers
5. add `POST /system/actions/enablement/check`
6. add one B2BI pilot manifest with two actions

Do not start with ontology persistence, dashboard UI, and enterprise runtime resolvers all at once.

## 11. Testing Plan

Add tests at three levels.

### Unit tests

- model normalization
- evaluator blocker aggregation
- resolver selection by dependency type

### Integration tests

- permission allowed but feature flag disabled
- permission denied
- permission allowed and feature enabled
- missing manifest entry

### Pilot tests

- B2BI action returns blocked when secrets are missing
- B2BI action returns blocked when filesystem is absent

## 12. Exit Criteria For Slice 1

Slice 1 is complete when:

- OSS modules compile and are wired into the parent build
- the enablement API can evaluate at least one scoped action end to end
- B2BI can declare at least one requirement manifest entry against the new SPI
- tests cover permission + feature-flag composition
