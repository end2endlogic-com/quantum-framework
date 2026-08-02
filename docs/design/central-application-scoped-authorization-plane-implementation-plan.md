# Implementation Plan — Central Application-Scoped Authorization Plane

- **Design:** `design_c3eaa8a9a8a544548a7a45ceaeab898c` — *Central application-scoped authorization plane*
- **TODO stack:** `central-application-scoped-authorization-plane` (`bae39e3a-4cb2-469f-b88a-89fe51d529ca`, 6 items)
- **Workflow:** `sdlc.safe_change_delivery.v1` / step `implementation_plan`
- **Governing repo:** `/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework`
- **Linked enterprise repo:** `/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise`
- **Status:** plan only — no product code changed in this step.

---

## 0. Ground truth & preconditions (verified by scouting the working tree)

### 0.1 Version drift (must reconcile before coding)
The objective says both repos base against **1.4.0-SNAPSHOT**. The actual working tree is:
- framework aggregate pom `<version>` = **1.4.1-SNAPSHOT**; branch = **1.4.2-SNAPSHOT**.
- enterprise `quantum-enterprise-parent` `<version>` = **1.4.1-SNAPSHOT**; `quantum.version` = **1.4.1-SNAPSHOT**; each auth module pins `quantum.framework.version=1.4.1-SNAPSHOT`.

**Action:** treat the live tree (1.4.1 line) as authoritative — the in-progress auth work below already exists at this line. Do **not** rebase to 1.4.0. Flag the discrepancy in the change-delivery evidence; do not silently change versions.

### 0.2 Preserve unrelated dirty work
The tree carries unrelated in-flight work that MUST be preserved (seed, ontology, migration, billing/test-db changes). Do not revert or reformat:
- `quantum-*/src/test/**` seed/ontology/migration test edits, `**/application.properties` test config, `SeedContextVariableResolver`, `SeedAdminResource`, `MongoDbInitResource*`, `MongoTenantDataExpirationProviderIT`, etc.
- TODO note on the stack: *"Preserve unrelated dirty billing and test-database changes."*

### 0.3 Auth work already present in the tree (do NOT re-create — extend/verify)
This design is **partially delivered**. Confirmed already-present artifacts:

| Artifact | Location | State |
|---|---|---|
| `AuthorizationProvider` SPI | `quantum-models/.../model/auth/AuthorizationProvider.java` | **untracked (new)** — `checkRules(...)`, javadoc "must fail closed" |
| RuleContext delegation seam | `quantum-morphia-repos/.../securityrules/RuleContext.java` (pkg `security.runtime`) | **modified** — delegates to `AuthProviderFactory.getAuthProvider()` when it is an `AuthorizationProvider`, fails closed on null (~L1308) |
| RuleContext delegation test | `quantum-morphia-repos/.../securityrules/RuleContextAuthorizationProviderTest.java` | **untracked (new)** |
| SecurityFilter provider wiring | `quantum-security-runtime/.../rest/filters/SecurityFilter.java` | **modified** |
| SecurityFilter delegated-provider test | `quantum-security-runtime/.../rest/filters/SecurityFilterDelegatedProviderTest.java` | **untracked (new)** |
| App-scope grant model | `quantum-models/.../model/security/UserRealmRole.java` | present — `authorizedApplications`, `defaultApplication`, `APPLICATION_WILDCARD="*"` |
| App-scope resolver (pure fn) | `quantum-models/.../model/auth/ApplicationAuthorizationResolver.java` | present — LEGACY/RESOLVED/AMBIGUOUS/DENIED |
| App-scoped login | `quantum-models/.../model/auth/AuthProvider.java` | present — `login(user,pw,applicationId,realmId)`, `LoginPositiveResponse.audiences/activeApplication` |
| Role resolution (union of sources incl. groups) | `quantum-morphia-repos/.../morphia/IdentityRoleResolver.java` | present — TOKEN/CREDENTIAL/REALM/**USERGROUP** |
| **Enterprise** decision facade | `quantum-enterprise/quantum-auth-service/.../resource/AuthzFilterResource.java` `GET /v1/authz/filter` | present — thin facade over `RuleContext.checkRules` |
| **Enterprise** remote provider | `quantum-enterprise/quantum-auth-client-provider/.../QuantumAuthServiceAuthProvider.java` | present — implements `AuthProvider` + `ClaimsAuthProvider` + `AuthorizationProvider.checkRules` via generated SDK; fails fast on no decision |
| **Enterprise** generated SDK (java/py/ts) | `quantum-enterprise/quantum-auth-service/generated/quantum-auth-service-sdk/{java,python,typescript}` | present — GAV `com.e2eq.framework.auth:quantum-auth-service:0.1.0`, pkg `com.e2eq.framework.auth.sdk` |
| SDK-gen config + contract | `quantum-auth-service/contracts/quantum-auth-service-sdk.yml` + `.openapi.yaml` (334 paths) | present |

### 0.4 The actual gap this plan closes
The **identity/provider/delegation** layer is done. The **application-scoping of the authorization decision** is NOT. Specifically:
- `SecurityURIHeader`/`SecurityURIBody`, `PrincipalContext`, `ResourceContext`, and `Rule` carry **no `applicationId`**.
- `Policy` selection and `RuleContext.checkRules` do **not** scope by application.
- `Application` (`quantum-models/.../model/security/Application.java`) is an **empty marker entity** — no vocabulary (FunctionalDomain/Action) catalog, no admission validation.
- No explicit `applicationId` in the decision contract (OpenAPI `/v1/authz/filter`) → SDKs cannot pass it.
- No compatibility rule keeping **legacy unscoped policies local-only** / non-matching to a named application.

> helixor-sdk-gen is an **out-of-band Python CLI** (`/Users/mingardia/dev/helixor/helixor-sdk-gen`), NOT a Maven plugin. SDKs are regenerated by running the CLI, then the `generated/.../java` module builds in the reactor. quantum-auth-service currently lacks a `scripts/verify-generated-sdk.sh` drift check (system/billing have one) — add it.

---

## 1. Scope, boundaries, non-negotiables

- **Framework stays clean:** no dependency on `quantum-enterprise` or Helixor. All remote/consolidated behavior lives behind the existing `AuthorizationProvider` SPI in `quantum-models`; the impl lives in enterprise `quantum-auth-client-provider`.
- **Fail closed** at every seam: missing `applicationId`, unknown application, missing catalog entry, provider returns null, remote unavailable → **DENY**.
- **Additive & backward-compatible:** new `applicationId` fields are optional on the wire; absent → legacy behavior. Legacy unscoped policies never match a *named* application unless explicitly configured.
- **No hand-written seam DTOs/clients:** every Quantum↔auth-service seam goes through helixor-sdk-gen output. If the generator lacks a capability, enhance `helixor-sdk-gen` (+ cover in `helixor-sdk-test`) — do not hand-roll.
- **Ownership (per design):** auth-service owns Application, FunctionalDomain/Action vocabulary, UserGroup roles, UserRealmRole grants, and Policy/Rule persistence in the auth realm. Functional app DBs own only business entities. Ontology keeps semantic TBox; auth owns the authorization-vocabulary projection.

---

## 2. Work breakdown — mapped to the 6 TODO items

Each item lists concrete files (module-relative), the change, fail-closed points, and the design verification item it satisfies.

### TODO 1 — Inventory overlapping changes & freeze the application/realm authorization contract
*(Section 0 above is the inventory. This item finalizes the contract shape before touching code.)*

Deliverables:
1. **Freeze the `applicationId` contract** — a short ADR appended to this doc:
   - `applicationId` is a stable string ref (matches `Application.refName`), realm-qualified.
   - Wildcard `*` = "all applications in realm" (mirror `UserRealmRole.APPLICATION_WILDCARD`).
   - Absent/null `applicationId` on a request = **legacy scope** (local-only, unscoped policies; never matches a named application).
   - Decision contract adds `applicationId` as an **optional** request field and echoes a `decisionScope` (already present on `AuthzFilterResponse`) that names the resolved application.
2. **Confirm `ApplicationRepo` exists** in `quantum-morphia-repos` and its realm scoping (auth realm `quantum-auth` / `system-com`). If absent, it is added in TODO 3.
3. Record the freeze in `brain_design` (`implementation[]`) and the TODO notes.

Verification: none directly — gating artifact for the rest.

### TODO 2 — Provider-neutral application scope + local policy resolution abstractions in **quantum-framework**
Thread `applicationId` through the decision path and introduce a provider-neutral local resolver. All in **quantum-models** + **quantum-morphia-repos** (no enterprise deps).

**quantum-models (`com.e2eq.framework.model.securityrules` / `model.security`):**
- `SecurityURIHeader.java` — add optional `applicationId` field (+ builder, equals/hashCode, wildcard match support). This is the rule-matching key, so wildcard semantics must match `WildCardMatcher`.
- `SecurityURIBody.java` — if application scope belongs to the "resource body" side, add there instead / additionally; decide during TODO 1. (Header is the natural home since it's identity+area+domain+action.)
- `PrincipalContext.java` — add optional `applicationId` (resolved active application for the principal; default from `UserRealmRole.defaultApplication`). Builder + immutability preserved.
- `ResourceContext.java` — add optional `applicationId` (application the target resource belongs to).
- `Rule.java` — add optional `applicationId` on the rule (so a policy's rules can be application-qualified); default null = unscoped/legacy.
- `SecurityCheckResponse.java` — add `resolvedApplicationId` / reflect application in the decision provenance (additive).

**quantum-morphia-repos (`com.e2eq.framework.security.runtime` + `.morphia`):**
- `RuleContext.java`:
  - Extend `getApplicableRulesForPrincipalAndAssociatedRoles(...)` and `getEffectiveRulesForRequest(...)` to **filter by `applicationId`**: a rule matches iff `rule.applicationId` is null (legacy) **and** the request is legacy-scoped, OR `rule.applicationId` equals the request app, OR `rule.applicationId == "*"`. Legacy unscoped rules do **not** match a named-application request (compatibility rule).
  - Add an explicit **local policy resolver abstraction**: extract the local (PolicyRepo-backed) resolution behind a small interface, e.g. `LocalPolicyResolver` (`@ApplicationScoped` default impl `MorphiaLocalPolicyResolver`) so the decision engine is provider-neutral and unit-testable without Mongo. Keep the existing delegation-to-`AuthorizationProvider` seam unchanged.
  - **Fail closed:** if a request presents a named `applicationId` that resolves to no known application (via `ApplicationRepo`), and no wildcard/legacy rule applies → DENY. Never widen.
  - Wire `applicationId` into the request cache key (`RuleContextRequestCache`) and the compiled `RuleIndex` key so caching stays correct per application.
- `PolicyRepo.java` — add finder(s) that select policies by `applicationId` + realm + principal (role/user), plus the legacy-unscoped set, honoring the compatibility rule.
- `WildCardMatcher` usage — ensure `applicationId` participates in URI expansion/matching consistently.

**Enforcement call sites — populate `applicationId` (no logic change, just carry it):**
- `quantum-security-runtime/.../rest/filters/SecurityFilter.java` — resolve active application from token (`ApplicationAuthorizationResolver` over `LoginPositiveResponse.activeApplication`/`audiences` + `UserRealmRole.defaultApplication`, honoring `X-Application` header if adopted) and set it on `PrincipalContext`; derive `ResourceContext.applicationId` from the model's owning application. **Fail closed** when the token is application-ambiguous (`AMBIGUOUS`/`DENIED` outcomes) for an app-scoped resource.
- `quantum-morphia-repos/.../interceptors/PermissionRuleInterceptor.java`, `MorphiaRepo.java`, `RepoSecurityFilterBuilder.java` — pass through the ambient `applicationId` from `SecurityContext` (no new resolution here).
- `quantum-query-rest/.../QueryGatewayResource.java`, `quantum-system-rest/.../PermissionResource.java` — accept optional `applicationId` and thread it into the contexts.

Verification satisfied: **Application/realm policy isolation tests**, **Provider delegation & fail-closed tests** (delegation already present; extend with app-scope).

### TODO 3 — Auth-owned application vocabulary, group-role policy resolution, validation & REST contracts in **quantum-auth-service** (enterprise)
All in `quantum-enterprise/quantum-auth-service` (+ shared entities in framework `quantum-models`/`quantum-morphia-repos` where they are framework-owned model classes).

**Vocabulary (application + functional area/domain/action catalog):**
- Flesh out `Application` (`quantum-models/.../model/security/Application.java`): add `refName` usage as the app id, `displayName`, and an owned **authorization-vocabulary projection** — the set of `FunctionalDomain`/`FunctionalAction` valid for the application (either embedded refs or a join). Keep it a framework model (Morphia entity) so the local engine can read it; auth-service owns *writes*.
- Add/confirm `ApplicationRepo` in `quantum-morphia-repos` (auth-realm scoped).
- **Admission validation service** in auth-service (`.../auth/service/`), e.g. `AuthorizationVocabularyAdmissionService`: validate that a submitted Policy/Rule references only area/domain/action pairs present in the target application's catalog; reject unknown vocabulary (fail closed). Mirror the ontology TBox-admission pattern (see `quantum-ontology-service`), but for the authorization projection.

**Group-role → role-policy resolution (central):**
- Reuse `IdentityRoleResolver` (already unions TOKEN/CREDENTIAL/REALM/USERGROUP → effective roles). Add the **role → role-policy selection** step scoped by `applicationId` + realm: given effective roles, select `Policy` rows where `principalType=ROLE` and `principalId ∈ effectiveRoles`, filtered by application per TODO 2's compatibility rule. Confirm `AuthzFilterResource`/`checkRules` path applies this for group-derived roles identically to direct roles.
- Add provenance: decision echoes which roles (and their `RoleSource`, incl. `USERGROUP`) contributed, per design "provenance".

**REST contracts (code-first, then regenerate SDK in TODO 4):**
- `AuthzFilterResource` (`/v1/authz/filter`) — add optional `applicationId` request param + `resolvedApplicationId`/`decisionScope` in `AuthzFilterResponse`. Keep `includeCheck` behavior.
- New/extended resources under `/v1/authz` (or `/security/…`) for:
  - **Application catalog** CRUD/read (`GET/POST /v1/applications`).
  - **Functional-domain/action catalog** read + admission (`GET /v1/applications/{app}/vocabulary`, admission on policy write).
  - **Policy CRUD/admission** — extend existing `/security/permission/policies/**` to carry `applicationId` and run vocabulary admission before persist.
  - **Provenance** — decision response provenance (roles, sources, matched rules, resolved app).
- **Seed packs:** update `quantum-auth-service/src/main/resources/seed-packs/platform-system-admin/datasets/{applications,policies,userGroups}.jsonl` to include application-scoped examples for tests.

**Fail-closed points:** unknown application, empty catalog for a named app, policy referencing out-of-catalog vocabulary, missing role→policy match → DENY / reject.

Verification satisfied: **Group role to role-policy resolution tests**, **Vocabulary admission validation tests**.

### TODO 4 — Regenerate Java/TS/Python auth-service SDKs with helixor-sdk-gen & wire the enterprise provider
- Update `quantum-auth-service/contracts/quantum-auth-service-sdk.yml` operation grouping if new ops added (keep `authorization`/`policies`/`identity` resources coherent).
- Re-export the code-first OpenAPI (`quantum-auth-service.openapi.yaml`) from the running/annotated resources (SmallRye export), then run the generator:
  ```
  python -m helixor_sdk_gen.cli generate \
    quantum-auth-service/contracts/quantum-auth-service-sdk.yml \
    quantum-auth-service/contracts/quantum-auth-service.openapi.yaml \
    --out quantum-auth-service/generated/quantum-auth-service-sdk --local
  ```
  (env `HELIXOR_SDK_GEN_ROOT=/Users/mingardia/dev/helixor/helixor-sdk-gen`.)
- **Add `quantum-auth-service/scripts/verify-generated-sdk.sh`** (mirror system/billing) so drift is caught in CI; wire it into the build/review step.
- Bump generated SDK artifact version if the contract changed (currently hard-pinned `0.1.0` in `quantum-auth-client-provider`; update both GAV and the dependency, or adopt a version property). Refresh `GeneratedContract.SPEC_SHA256`/`CONTRACT_VERSION` used by the `/contract-hash` handshake.
- Wire provider: `QuantumAuthServiceAuthProvider.checkRules(...)` now sends `applicationId` on `getAuthzFilter(...)` and maps `resolvedApplicationId`/provenance back into `SecurityCheckResponse`. Still **fail fast** on no decision.
- Regenerate/refresh the Python and TypeScript clients (`system-ux useAuthClient`) so the `applicationId`/decisionScope fields surface.

Verification satisfied: **Generated SDK contract test**.

### TODO 5 — Unit/integration/persistence-boundary tests + targeted builds
Add tests (respect existing `*IT` = integration, `*Test` = unit conventions; Mongo IT via existing `MongoDbInitResource`):

| Test | Module | Asserts | Design item |
|---|---|---|---|
| `RuleContextApplicationScopeTest` (extend `RuleContextAuthorizationProviderTest`) | quantum-morphia-repos | named-app request only matches app/wildcard rules; legacy request only matches legacy rules; unknown app → DENY | Isolation; fail-closed |
| `ApplicationPolicyIsolationIT` | quantum-framework (IT) | policies in app A never leak to app B in same realm; cross-realm isolation holds | Application/realm policy isolation |
| `SecurityFilterDelegatedProviderTest` (extend) | quantum-security-runtime | provider path forwards bearer + application/realm scope; provider null/unavailable → DENY | Provider delegation & fail-closed |
| `GroupRoleToRolePolicyResolutionIT` | quantum-morphia-repos or quantum-framework | UserGroup role → role Policy selected & applied, app-scoped, with `USERGROUP` provenance | Group role → role-policy |
| `VocabularyAdmissionValidationTest` | quantum-auth-service (enterprise) | policy referencing out-of-catalog area/domain/action rejected; valid admitted | Vocabulary admission |
| `AuthServiceSdkContractTest` + `verify-generated-sdk.sh` | quantum-auth-service (enterprise) | generated SDK matches committed OpenAPI (no drift); `applicationId` present on decision op | Generated SDK contract |
| `FunctionalAppPersistenceBoundaryIT` | quantum-framework (IT) | functional app DB holds only business entities; Application/Policy/UserGroup/UserRealmRole persist only in the auth realm | Functional-app persistence boundary |
| `ApplicationAdmissionFailClosedIT` | quantum-framework (IT) | missing application context on an app-scoped resource → DENY | Fail-closed |

Targeted builds (fast → full):
```
# framework — compile + focused module tests
mvn -q -pl quantum-models,quantum-morphia-repos,quantum-security-runtime -am compile
mvn -q -pl quantum-morphia-repos -Dtest='RuleContext*Test,GroupRole*' test
mvn -q -pl quantum-framework -Dit.test='ApplicationPolicyIsolationIT,FunctionalAppPersistenceBoundaryIT,ApplicationAdmissionFailClosedIT' verify
# enterprise — auth-service + provider + sdk
mvn -q -pl quantum-auth-service,quantum-auth-client-provider -am test
quantum-auth-service/scripts/verify-generated-sdk.sh
```

### TODO 6 — Security/code review, durable evidence, migration follow-ups
- Run `/security-review` and `/code-review` (or `code.review`) on the diff; focus: fail-closed completeness, no framework→enterprise leak, cache-key correctness, wildcard-match safety.
- Update `brain_design` `implementation[]` and the TODO stack (mark items done) with changed-file lists, commands, and results.
- **Migration follow-ups to document (not necessarily code now):**
  - Backfill/annotate existing Policies with `applicationId` (or leave legacy-unscoped by design). Provide a changeset guide.
  - Reconcile the SDK `0.1.0` version pin.
  - Reconcile the 1.4.0 vs 1.4.1/1.4.2 version drift (Section 0.1).

---

## 3. Sequencing & dependencies

```
TODO 1 (freeze contract) ─┬─> TODO 2 (framework: thread applicationId + local resolver)
                          │        │
                          │        └─> TODO 5 framework tests (isolation, fail-closed, boundary)
                          └─> TODO 3 (enterprise: vocabulary, admission, group-role, REST)
                                   │
                                   └─> TODO 4 (regenerate SDK + wire provider) ─> TODO 5 SDK/enterprise tests
                                                                                        │
                                                                                        └─> TODO 6 (review + evidence)
```
- TODO 2 (framework) and the entity additions in TODO 3 that live in `quantum-models` should land together so both repos compile.
- TODO 4 must run **after** the auth-service REST contract in TODO 3 is final (OpenAPI is code-first / exported from resources).

## 4. Risks & mitigations
- **Cache correctness:** adding `applicationId` to matching without adding it to `RuleContextRequestCache`/`RuleIndex` keys would cross-contaminate decisions. → key-inclusion is an explicit TODO-2 acceptance check.
- **Compatibility regressions:** legacy callers send no `applicationId`. → additive optional fields + explicit "legacy request matches only legacy rules" rule; covered by `RuleContextApplicationScopeTest`.
- **SDK drift:** no Maven-plugin generation; manual CLI step. → add `verify-generated-sdk.sh` + contract test to gate.
- **Framework purity:** entity/vocabulary must stay in `quantum-models`/`quantum-morphia-repos`; only *write/admission/remote* logic in enterprise. → `FunctionalAppPersistenceBoundaryIT` + a dependency check that framework modules declare no enterprise/Helixor deps.
- **Version drift (0.1):** could cause enterprise/framework artifact mismatch. → confirm both stay on 1.4.1 line for this change; document.

## 5. Acceptance = design "Verification" section, fully covered
Provider delegation/fail-closed ✔ (TODO 2/5) · Application/realm isolation ✔ (TODO 2/5) · Group-role→role-policy ✔ (TODO 3/5) · Vocabulary admission ✔ (TODO 3/5) · Generated SDK contract ✔ (TODO 4/5) · Functional-app persistence boundary ✔ (TODO 5).

## 6. Explicit non-goals (this change)
- No new identity provider / OIDC changes (JWKS validation stays local).
- No migration of *business* data; only authorization vocabulary/policy ownership.
- No UI beyond regenerating the TS client fields; `system-ux` UX work is a follow-up.
