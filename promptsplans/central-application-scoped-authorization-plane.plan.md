# Implementation Plan — Central Application-Scoped Authorization Plane

- **Design:** `design_c3eaa8a9a8a544548a7a45ceaeab898c` — *Central application-scoped authorization plane*
- **TODO:** `central-application-scoped-authorization-plane` (list `bae39e3a-4cb2-469f-b88a-89fe51d529ca`, 6 items)
- **Governing repo:** `/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework` (OSS framework)
- **Linked enterprise repo:** `/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise`
- **Workflow:** `sdlc.safe_change_delivery.v1` — step `implementation_plan` (planner; no production code changed in this step)

> **Baseline correction:** the objective says both repos base against `1.4.0-SNAPSHOT`; the actual working-tree version of **both** repos is **`1.4.1-SNAPSHOT`**. Plan against 1.4.1-SNAPSHOT.

---

## 1. Current-state reality (what already exists — do NOT rebuild)

A large fraction of this design is already scaffolded. The delta is **threading an explicit `applicationId` scope through the decision path, resolving group roles + selecting policies by that scope centrally, and closing the fail-closed / persistence-boundary gaps** — not greenfield modeling.

**Framework (`quantum-framework`) — module layout for this work:**
- Security value objects: `quantum-models/.../model/securityrules/` (`PrincipalContext`, `ResourceContext`, `SecurityContext`, `SecurityURI`/`SecurityURIHeader`/`SecurityURIBody`).
- Persisted security entities: `quantum-models/.../model/security/` (`Policy`, `Rule`, `FunctionalDomain`, `FunctionalAction`, `UserGroup`, `UserRealmRole`).
- Engine + repos + interceptors: `quantum-morphia-repos/` (`securityrules/RuleContext.java` — **FQN is `com.e2eq.framework.security.runtime.RuleContext`**, note path/package mismatch; `PolicyRepo`, `IdentityRoleResolver`, `interceptors/PermissionRuleInterceptor`).
- Request-time filters: `quantum-security-runtime/` (`SecurityFilter`, `RolesAugmentor`).
- Auth SPI: `quantum-models/.../model/auth/` (`AuthProvider`, `AuthorizationProvider`, `ApplicationAuthorizationResolver`, `RoleAssignment`, `RoleSource`).

**Already in place (relevant to this design):**
1. **Provider seam** — `AuthorizationProvider extends AuthProvider` with `checkRules(...)`; `RuleContext.checkRules` already delegates to it when `AuthProviderFactory.getAuthProvider() instanceof AuthorizationProvider`, and **fails closed** on a null decision. *(This is in-flight uncommitted work — Group A dirty tree.)*
2. **Two provider tests already drafted (uncommitted):** `RuleContextAuthorizationProviderTest`, `SecurityFilterDelegatedProviderTest`.
3. **Remote delegation impl** — `quantum-enterprise/quantum-auth-client-provider/.../QuantumAuthServiceAuthProvider` implements `AuthorizationProvider`, calls the central `/v1/authz/filter` over the **generated SDK**, and runs a `/contract-hash` semver+sha256 handshake (fail-fast, no silent fallback).
4. **Central service** — `quantum-enterprise/quantum-auth-service` already exposes: `/v1/authz/filter` (`AuthzFilterResource`, injects framework `RuleContext`), `/system/permissions/check|fd/evaluate|role-provenance`, policy CRUD (`/security/permission/policies`), application catalog (`/api/security/application`), `/security/functionalDomain`, `/user/usergroup`, `/security/user-realm-roles` (incl. `.../applications/evaluate`).
5. **Generated SDKs (java/ts/python)** already checked in at `quantum-auth-service/generated/quantum-auth-service-sdk/{java,python,typescript}`; gen config `quantum-auth-service/contracts/quantum-auth-service-sdk.yml`; OpenAPI source `quantum-auth-service/contracts/quantum-auth-service.openapi.yaml`; embedded `GeneratedContract.SPEC_SHA256` + `CONTRACT_VERSION`.
6. **Group-role resolution** — `IdentityRoleResolver` already unions token + credential + `UserRealmRole` + `UserGroup` roles with `RoleSource` provenance; `UserGroup.roles[]` exists; `/system/permissions/role-provenance` exposes provenance.
7. **Login-time application scope** — `UserRealmRole.authorizedApplications[]` / `defaultApplication`; `ApplicationAuthorizationResolver` (decisions locked 2026-07-12 / 07-20, `"*"` wildcard).

**The gap this design closes:** application scope exists **only in the auth/token-audience layer**. It is **absent** from every authorization-*decision* object — `PrincipalContext`, `ResourceContext`, `Policy`, `SecurityURIHeader`/`Body`, and the `/v1/authz/filter` + `CheckRequest`/`EvaluateRequest` contract (`applicationId` appears 0× in those, confirmed). Policy selection today is by string identity match only; there is no application dimension.

---

## 2. Scope boundaries — preserve unrelated dirty work

**Do NOT touch (preserve in place):**
- **Framework Group B (test-infra):** `MongoDbInitResource(+Test)`, `TestMorphiaDataStore`, `MigrationStartupRealmsTestProfile`, `TestLocks`, all `application.properties` under `src/test/resources`, `AgentConfigResourceIT`, `MongoTenantDataExpirationProviderIT`.
- **Framework Group C (seed/ontology tests + seed main):** all `service/seed/*` tests, `SeedContextVariableResolver`, `SeedAdminResource`, `quantum-ontology-mongo`/`-policy-bridge` IT tests.
- **Enterprise billing branch (large, active):** everything under `quantum-billing-*`, the `quantum-system-service` billing bindings/offers/selection files, and all regenerated billing/system SDKs.
- **Enterprise auth files entangled with the billing branch:** `QuantumAuthServiceAuthProvider.java`, `GitHubOAuthLoginService.java`, auth `application.properties` are already `M` on the billing branch — **coordinate before editing**; prefer additive edits and confirm they do not collide with the billing commit.

**Fair game to modify/extend:** framework Group A auth seam (RuleContext, SecurityFilter, AuthorizationProvider, the two new provider tests), plus new files. In enterprise: `quantum-auth-service` REST/service + contract + regenerated auth SDK, `quantum-auth-client-provider` (coordinated).

**Hard rule:** the OSS framework must not gain any `quantum-enterprise` or Helixor dependency. All enterprise↔framework coupling stays behind the framework-owned `AuthorizationProvider` SPI + the generated SDK.

---

## 3. Workstreams (mapped to the 6 TODO items)

### WS-1 — Inventory & freeze the application/realm authorization contract  *(TODO #1)*
- Freeze the decision contract shape as an **additive** change: `applicationId` (nullable/optional) on the decision input + Policy selection, echoed in the response for provenance.
- Confirm the semantics table (below) and record it as the contract-of-record note in the design.
- **Done:** contract semantics agreed; no code yet.

**`applicationId` semantics (the compatibility contract):**
| Policy `applicationId` | Context `applicationId` | Match? |
|---|---|---|
| `null` (legacy/unscoped) | `null` | yes (local-only compat) |
| `null` (legacy/unscoped) | named `A` | **no** — legacy must not match a named application unless explicitly opted in |
| `A` | `A` | yes |
| `A` | `B` or `null` | no |
| `*` (explicit wildcard) | any | yes (audit-logged) |
- **Fail closed:** context has a named application but no catalog entry / no matching scoped policy / provider unreachable ⇒ **DENY**, typed diagnostic.

### WS-2 — Framework: provider-neutral application scope + local resolver  *(TODO #2)*
Files (framework, additive):
- `quantum-models/.../model/securityrules/PrincipalContext.java` — add `String applicationId` (+ builder `withApplicationId`, getter, equals/hashCode/toString). Default `null`.
- `quantum-models/.../model/securityrules/ResourceContext.java` — add `String applicationId` (+ builder, getter). Keep lowercase-normalization consistent with siblings.
- `quantum-models/.../model/securityrules/SecurityURIBody.java` — add `application` scope dimension (default `"*"`), mirroring the existing `realm` dimension so rule scope matching gains an application axis **without** changing the `SecurityURIHeader` wildcard-trie key (avoids reindexing/ABI break).
- `quantum-models/.../model/security/Policy.java` — add `String applicationId` (nullable) as the coarse selection filter.
- `quantum-morphia-repos/.../morphia/PolicyRepo.java` — add application-scoped selection: `getPoliciesForIdentities(realm, identities, applicationId)` overloads that (a) include policies whose `applicationId` equals the context app or is `*`, and (b) include `applicationId==null` legacy policies **only when** the context application is null (or an explicit opt-in flag is set). Keep existing signatures for compatibility.
- `quantum-morphia-repos/.../securityrules/RuleContext.java` — in the **local** (non-delegated) branch, pass the context `applicationId` into policy selection + `SecurityURIBody` application matching; **fail closed** when a named application is present but resolves to no catalog entry / no policy. The already-present delegated branch stays.
- `quantum-security-runtime/.../rest/filters/SecurityFilter.java` — populate `applicationId` on the built `PrincipalContext`/`ResourceContext` from the token (`azp`/`aud` active application per `ApplicationAuthorizationResolver`) and/or request. Additive; null when absent (legacy).
- **Config:** a property (e.g. `quantum.security.application-scope.require-named=false`) to keep legacy behavior default-on and let deployments enforce named-application-only.
- **Done:** local resolver honors application scope + fails closed; contexts carry `applicationId`; all existing callers compile unchanged (fields optional).

### WS-3 — Auth service: auth-owned vocabulary, group→role policy resolution, admission, REST  *(TODO #3)*
Files (enterprise `quantum-auth-service`):
- `.../resource/AuthzFilterResource.java` — accept an `applicationId` query param; set it on the `PrincipalContext`/`ResourceContext` before `ruleContext.checkRules(...)`; echo it in `AuthzFilterResponse`. Fail closed (400/deny) when required application context is absent under enforced mode.
- Group-role resolution — ensure the central decision path resolves **effective roles = direct + realm (`UserRealmRole`) + active `UserGroup` roles** before policy selection (reuse `IdentityRoleResolver`); expose via `/system/permissions/role-provenance` (already present) and use in `check`/`fd/evaluate`.
- Vocabulary **admission validation** — extend `.../service/RegistryBackedApplicationValidator.java` (and/or a new `PolicyAdmissionValidator`) so persisting a `Policy` validates that referenced `area` / `functionalDomain` / `action` / `applicationId` exist in the catalog; reject unknown vocabulary with a typed error (fail closed). Wire into the policy CRUD write path (`PolicyResource` / `PolicyRepo` pre-persist).
- **Ownership / persistence boundary** — confirm that in **remote** mode a functional application delegates all policy/vocabulary reads and never persists `policy`/`functionalDomain`/`application`/`userGroup`/`userRealmRole` documents locally; auth-service (auth realm DB) is the persistence owner. Reuse the `quantum.mode=embedded|remote` fail-loud seam; do NOT “fix” those throws.
- **Done:** central service selects policies by application+realm+effective-roles, validates vocabulary on admission, fails closed on missing app/catalog.

### WS-4 — Regenerate SDKs + wire the enterprise provider  *(TODO #4)*
1. Edit OpenAPI `quantum-auth-service/contracts/quantum-auth-service.openapi.yaml`:
   - Add `applicationId` (optional) to `getAuthzFilter` params, `CheckRequest`, `EvaluateRequest`, `RoleProvenanceRequest`, `AuthzFilterResponse`, and the `Policy` schema.
2. Regenerate (command from `contracts/quantum-auth-service-sdk.yml` header):
   ```
   PYTHONPATH=/Users/mingardia/dev/helixor/helixor-sdk-gen/src \
     python -m helixor_sdk_gen.cli generate \
     quantum-auth-service/contracts/quantum-auth-service-sdk.yml \
     quantum-auth-service/contracts/quantum-auth-service.openapi.yaml \
     --out quantum-auth-service/generated/quantum-auth-service-sdk --local
   ```
   Regenerates java/ts/python; refreshes `GeneratedContract.SPEC_SHA256` + `CONTRACT_VERSION`.
3. Enterprise provider `quantum-auth-client-provider/.../QuantumAuthServiceAuthProvider.checkRules(...)` — forward `applicationId` (from `ResourceContext`/`PrincipalContext`) to `getAuthzFilter`. **(Coordinate — file is entangled with the billing branch.)**
4. If `helixor-sdk-gen` lacks a needed capability, **enhance the generator + cover in `helixor-sdk-test`** — never hand-write client code or copy DTOs.
5. **Done:** SDKs regenerated, contract hash bumped, provider forwards application scope, `/contract-hash` handshake still passes.

### WS-5 — Tests + targeted builds  *(TODO #5)* — see §5 matrix.

### WS-6 — Security/code review, durable evidence, migration follow-ups  *(TODO #6)*
- Run `sdlc.safe_change_delivery.v1` security gate (`helixor.security_review_decision.v1`) over the diff; `/security-review`.
- Update the design `implementation` + TODO items with evidence (files, commands, results).
- Document migration follow-ups: backfilling `applicationId` on existing policies, catalog seeding for existing functional domains, and the embedded→remote persistence cutover per realm.

---

## 4. Sequencing & dependencies

```
WS-1 (freeze contract)
  └─> WS-2 (framework: contexts + local resolver + fail-closed)   ← unblocks OSS build + local tests
  └─> WS-3 (auth-service: decision scope + admission)             ← depends on WS-2 model fields
         └─> WS-4 (OpenAPI edit → regen SDK → provider forwards)  ← depends on WS-3 REST shape
                └─> WS-5 (delegation/SDK-contract/persistence tests)
                       └─> WS-6 (review + evidence + migration notes)
```
Critical path is WS-2 → WS-3 → WS-4. WS-2 is self-contained in the OSS repo and can land first (framework must stay independently buildable/testable). WS-4 SDK regen must follow the OpenAPI edit and precede provider wiring.

---

## 5. Verification matrix (design’s 6 categories → concrete tests)

| Design verification item | Concrete test | Location |
|---|---|---|
| Provider delegation + fail-closed | extend existing `RuleContextAuthorizationProviderTest` + `SecurityFilterDelegatedProviderTest`; add null-decision + provider-unreachable ⇒ DENY | `quantum-morphia-repos/src/test/...securityrules/`, `quantum-security-runtime/src/test/...rest/filters/` |
| Application/realm policy isolation | new `ApplicationScopedPolicyIsolationTest` — policy for app `A`/realm `R1` must not match app `B`/realm `R2`; legacy (`null`) must not match a named app | `quantum-morphia-repos/src/test/...securityrules/` |
| Group role → role-policy resolution | new `GroupRoleToPolicyResolutionTest` — `UserGroup.roles` resolve into effective identities that select the matching role `Policy` | `quantum-morphia-repos/src/test/...` (build on `IdentityRoleResolver`) |
| Vocabulary admission validation | new `PolicyAdmissionValidationTest` — persisting a policy with unknown area/domain/action/application is rejected fail-closed | `quantum-auth-service/src/test/...` (or framework admission module) |
| Generated SDK contract test | new `AuthSdkContractTest` — SDK `SPEC_SHA256` matches the OpenAPI; `getAuthzFilter` carries `applicationId` | `quantum-auth-service` (align with system/billing `scripts/verify-generated-sdk.sh`; note: auth has none yet — add one) |
| Functional-app persistence boundary | new `FunctionalAppPersistenceBoundaryTest` — in remote mode the app DB holds no `policy`/`functionalDomain`/`application`/`userGroup`/`userRealmRole` docs; reads delegate | framework IT (reuse existing Mongo test harness — Group B, do not modify it) |

**Targeted builds:**
- Framework: `mvn -q -pl quantum-models,quantum-morphia-repos,quantum-security-runtime -am install -DskipTests` then run the above unit tests.
- Enterprise: `mvn -q -pl quantum-auth-service,quantum-auth-client-provider -am install -DskipTests` after SDK regen; run SDK contract test.

---

## 6. Risks & open decisions
- **`SecurityURI` axis choice:** application scope goes on `SecurityURIBody` (like `realm`), *not* `SecurityURIHeader`, to avoid breaking the wildcard-trie header key and rule-index ABI. Confirm no consumer relies on header cardinality.
- **Legacy default:** ship `require-named=false` so unscoped policies keep working; enforcing named-application-only is an opt-in per deployment (design “Compatibility”).
- **Enterprise entanglement:** `QuantumAuthServiceAuthProvider` + auth `application.properties` are dirty on the billing branch — sequence WS-4 edits to avoid clobbering; consider a dedicated branch/worktree.
- **RuleContext path/package mismatch** (`securityrules/` dir vs `security.runtime` package) — leave as-is (out of scope); note for callers.
- **`quantum-ontology-service` `/v1/security/decide`** is a parallel decision surface also calling `ruleContext.checkRules(...)` — ensure application scoping is consistent there or explicitly deferred.
- **Migration:** existing policies have `applicationId==null`; a follow-up changeset backfills scoped policies and seeds the application/vocabulary catalog before any deployment flips to `require-named=true`.

## 7. Out of scope / follow-ups
- Backfill/migration changeset for existing policies & catalog seeding (WS-6 follow-up).
- Wiring the remote ontology decision surface to application scope.
- Renaming the `RuleContext` directory to match its package.
