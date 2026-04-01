# Scoped Action Enablement Tracker

## Status Summary

- Overall status: `PHASE 4 COMPLETE, PHASE 5 READY`
- Design home: [SCOPED_ACTION_ENABLEMENT_DESIGN.md](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/docs/design/SCOPED_ACTION_ENABLEMENT_DESIGN.md)
- Implementation guide: [SCOPED_ACTION_ENABLEMENT_IMPLEMENTATION_GUIDE.md](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/docs/design/SCOPED_ACTION_ENABLEMENT_IMPLEMENTATION_GUIDE.md)
- Target layer: OSS framework core with enterprise resolver add-ons
- First pilot: B2BI

## Decisions Locked

- Atomic unit is the scoped action tuple: `area / functionalDomain / action`
- Policy remains the canonical authorization mechanism
- Feature flags remain separate from readiness
- Enablement is a composed framework service, not a replacement for policy
- Generic engine belongs in OSS framework
- Enterprise-specific dependency resolvers belong in `quantum-enterprise`

## Phase 0. Design

- [x] Confirm scoped action is the irreducible unit
- [x] Decide OSS framework vs enterprise split
- [x] Write architecture design
- [x] Write implementation guide
- [x] Create tracker

## Phase 1. OSS Framework Scaffolding

- [x] Add `quantum-action-enablement-models` to [framework/pom.xml](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/pom.xml)
- [x] Add `quantum-action-enablement-quarkus` to [framework/pom.xml](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/pom.xml)
- [x] Create `ScopedActionRef`
- [x] Create `ScopedActionRequirement`
- [x] Create `DependencyCheckRef`
- [x] Create `EnablementBlocker`
- [x] Create `ScopedActionEnablementStatus`
- [x] Create request/response DTOs for enablement API

## Phase 2. OSS Runtime Engine

- [x] Create requirement registry SPI
- [x] Create dependency resolver SPI
- [x] Create evaluator service
- [x] Implement permission resolver
- [x] Implement feature-flag resolver
- [x] Implement config/setting-present resolver
- [x] Implement generic entity-exists resolver
- [x] Add REST resource at `/system/actions/enablement`
- [x] Add focused tests for service evaluation and resolver behavior

## Phase 3. Enterprise Resolver Add-on

- [x] Add `quantum-action-enablement-enterprise` to [quantum-enterprise/pom.xml](/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise/pom.xml)
- [x] Implement managed-secret-configured resolver
- [x] Implement tenant-filesystem-provisioned resolver
- [x] Implement standard-mounts-present resolver
- [x] Implement workflow-runtime-reachable resolver
- [x] Add integration tests for enterprise blockers

## Phase 4. B2BI Pilot

- [x] Add B2BI manifest contributor
- [x] Register scoped action: `integration / guided_integration / create`
- [x] Register scoped action: `integration / exchange / create`
- [x] Register scoped action: `integration / workflow / create`
- [x] Register scoped action: `integration / inboundreceipt / create`
- [x] Add backend tests for pilot manifest resolution

## Phase 5. UI Consumption

- [ ] Replace dashboard setup heuristics with enablement API results
- [ ] Disable `Generate Integration` with blocker messaging
- [ ] Disable `Create Exchange` with blocker messaging
- [ ] Disable `Execute` with blocker messaging
- [ ] Disable `Receive File` with blocker messaging
- [ ] Add navigation links from blockers to relevant settings pages

## Phase 6. Optional Extensions

- [ ] Add ontology-backed persistence for action dependency metadata
- [ ] Add batch manifest query endpoint for shells and dashboards
- [ ] Add capability aggregation view for menus and home pages
- [ ] Add setup-progress summary endpoint

## Immediate Next Slice

This is the recommended next implementation slice:

1. wire B2BI Studio to `/system/actions/enablement/check`
2. replace dashboard setup heuristics with blocker-driven action state
3. disable `Generate Integration`, `Create Exchange`, `Execute`, and `Receive File` from enablement results
4. decide whether framework ships only framework-native manifests or also a seedable/persisted manifest option

## Risks To Watch

- Duplicating logic already hidden inside `PermissionResource` instead of reusing the same rule-evaluation path
- Letting enterprise concepts leak into OSS contracts
- Treating bare actions like `create` or `save` as meaningful without area/domain scope
- Building UI heuristics before the backend contract is stable

## Open Questions

- Should manifest registration be CDI-only in slice 1, or also support persisted ontology-backed definitions immediately?
- B2BI pilot currently uses framework-native `integration/...` scoped actions to stay aligned with existing resource domain resolution. Revisit only if runtime-specific actions need their own first-class policy domain later.
- Should the initial API support single-action only, or batch check from day one?
