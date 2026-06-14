# Bootstrap Pack Tracker

## Status Summary

- Overall status: `PHASE 1-3 COMPLETE, PHASE 4 IN PROGRESS`
- Design home: [BOOTSTRAP_PACK_DESIGN.md](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/docs/design/BOOTSTRAP_PACK_DESIGN.md)
- Target layer: Quantum OSS framework core
- First adopter: B2BI
- First shared consumer: `workflow-platform`

## Decisions Locked

- The replay primitive belongs in the Quantum framework, not in a single
  application and not in `workflow-platform`.
- Migrations, seed packs, and bootstrap packs remain separate mechanisms.
- Default apply mode is `APPLY_MISSING`.
- Applications own pack content; framework owns the replay engine.
- B2BI is the first concrete adopter pack.

## Phase 0. Design

- [x] Confirm this is a framework-level concern
- [x] Separate bootstrap packs from migrations and seed packs
- [x] Write framework design
- [x] Create tracker

## Phase 1. Framework Contracts

- [x] Add `BootstrapPackDefinition`
- [x] Add `BootstrapPackStepDefinition`
- [x] Add `ApplyBootstrapPackRequest`
- [x] Add `BootstrapPackRun`
- [x] Add `BootstrapPackStepRun`
- [x] Add `BootstrapStepResult`
- [x] Decide initial package location in `quantum-framework` for slice 1

## Phase 2. Framework Runner

- [x] Add `BootstrapPackContributor` SPI
- [x] Add `BootstrapPackStepHandler` SPI
- [x] Add pack registry service
- [x] Add pack apply service
- [x] Add validate-only flow
- [x] Add run-history persistence
- [x] Add focused tests for apply, validate, and replay semantics

## Phase 3. Startup and Admin Surfaces

- [x] Extend
  [FrameworkStartupCoordinator.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework/src/main/java/com/e2eq/framework/service/startup/FrameworkStartupCoordinator.java)
  with bootstrap-pack startup apply
- [x] Add pending bootstrap logging similar to pending seeds
- [x] Add framework admin endpoints for list/apply/validate
- [x] Add config keys for startup apply selection by realm/filter

## Phase 4. B2BI First Adopter

- [x] Define `b2bi-local-demo-defaults`
- [x] Add B2BI contributor bean
- [x] Add B2BI step handlers for shared-storage/demo provisioning
- [x] Verify startup auto-apply leaves no pending bootstrap packs for
  `system-b2bi-com` and `b2bi-com`
- [x] Move workflow-platform demo bootstrap defaults into the pack
- [x] Add B2BI tests for `APPLY_MISSING`
- [x] Add B2BI tests for `VALIDATE_ONLY`

## Phase 5. Workflow Platform Consumption

- [ ] Replace workflow-platform-local bootstrap assumptions with framework pack
  application
- [ ] Add workflow-platform publication helper step support
- [ ] Document workflow-platform as a consumer of framework bootstrap packs

## Immediate Next Slice

1. decide whether bootstrap run history stays in-memory or becomes persistent metadata
2. add richer admin filtering for product/profile if needed
3. decide whether/when to extract the contracts into a separate shared module
4. decide whether workflow publication should eventually be a generic framework step kind

## Verified Notes

- Bootstrap pack invocation is the authorization boundary; the framework runs
  bootstrap step handlers in internal ignore-rules mode so provisioning logic
  can perform the repo writes it owns without needing product-specific
  security-URI grants for each bootstrap operation.
- The B2BI startup path is now covered by
  [B2biBootstrapPackStartupIntegrationTest.java](/Users/mingardia/dev/mrisys/end2endlogic/quantum/b2bi-server/src/test/java/com/e2e/app/b2bi/service/B2biBootstrapPackStartupIntegrationTest.java)
  and confirms that startup ends with no pending bootstrap packs for the
  configured local-demo realms.
- The B2BI local-demo bootstrap defaults now own the workflow connector demo
  publication manifest too; the live workflow-platform smoke consumes
  `GET /workflow/connectors/b2bi/shared-storage/demo-manifest` instead of
  hardcoding the definition/version/deployment graph in Python.

## Open Questions

- Should pack records persist in the same realm-scoped database pattern as
  migrations and seed metadata?
- Should `REAPPLY` rerun all steps or only all idempotent steps?
- Should startup selection be profile-based, realm-based, or both?
- Should workflow-platform publication be represented as a generic framework
  step kind or as an application-owned step kind in B2BI first?
