# Control Plane / Data Plane Split — System Realm as a Deployable Service

Status: Draft
Owner: (assign)
Related: `TenantProvisioningService`, `UserManagement`, `Realm`, `RuleContext`,
`quantum-oauth-server`, `quantum-jwt-provider`
Consumers: a multi-tenant platform (`the system-plane app`, `the tenant-plane app`)

## 1. Problem

Cross-realm concerns are currently stored *inside a realm* and reached by
**hard-coding** the system realm. Three coupling points:

1. `EnvConfigUtils.getSystemRealm()` returns a constant default (`"system-com"`).
   It is treated as a compile-time fact rather than a configurable reference.
2. The realm catalog and credentials are written by passing that constant as the
   realm:
   - `RealmRepo.findByEmailDomain(emailDomain, ignoreRules, systemRealm)`
   - `credentialRepo.save(systemRealm, credential)`
   (both in `TenantProvisioningService.provisionTenant`).
3. `CredentialUserIdPassword` carries a `domainContext` that pins identity to a
   single realm, while user *profiles* are stored per-realm.

Consequences:

- Identity, realm catalog, and policy — things that span realms — live in a
  data-plane database, so they inherit a realm's lifecycle, config, scaling, and
  blast radius.
- There is no natural home for "this user, across many orgs." The seed of the
  right model already exists but is unused: `CredentialUserIdPassword.authorizedRealms`
  (`List<RealmEntry>`).
- `idp-app` was intended as an identity service but is mislabeled (its artifact
  is `psa-app`); the control plane was never actually built.

## 2. Goals / Non-Goals

Goals:

- Make the system realm a **configured reference with a default**, never a
  hard-coded constant.
- Give cross-realm concerns (identity, credentials, realm catalog, orgs,
  accounts, principal policies/roles, invitations) a **separately deployable**
  home with its own endpoints, config, and lifecycle.
- Support a **GitHub-style** model: global users invited into orgs; a user may
  belong to many orgs; within an org a user has org-specific roles/policies.
- **No behavior change on day one** — introduce the indirection with a local
  implementation that reproduces today's behavior, then stand up the remote
  service.

Non-Goals:

- Rewriting the policy engine (`RuleContext`) — only changing *where* principal
  identity/roles are resolved, not how rules evaluate.
- Forcing every app to remote mode. Local (embedded) mode stays first-class for
  single-deployment installs and tests.
- Changing the per-realm data-plane storage model (`DataDomain` row-level
  security stays as is).

## 3. Target Architecture

Two planes, both built from the framework.

Control plane — `quantum-system` (new deployable). Owns the cross-realm entities
and exposes their endpoints:

- `Realm` catalog (provision / lookup / directory)
- unified **Principal/Identity** (collapses `CredentialUserIdPassword` +
  `UserProfile`; generic over human/service/machine — see §6)
- `Organization`, `Account`
- principal-level `Policy` / roles and `Membership`
- invitations (lift the existing `AccessInviteService` from realm-scoped to
  org-scoped)
- token issuance — **reuses what already exists**: `quantum-oauth-server`
  (`OAuthTokenResource`, `OAuthAuthorizeResource`, `OAuthUserInfoResource`,
  `JwksResource`, `OAuthDiscoveryResource`, `OAuthClient`) and
  `quantum-jwt-provider` (`TokenUtils`, `JwtKeyResolver`,
  `CustomTokenAuthProvider`). The control plane *is* the OIDC issuer; it is not
  built from scratch.

`TenantProvisioningService` and `UserManagement` move here. The control plane is
small, security-critical, highly available, and has its own database (the
"system" database) and its own config (OIDC/Cognito, secrets).

Data plane — the realm-scoped server (what `the tenant-plane app` is). Owns ontology,
governance, business REST, per-realm databases. It **never reads the system
database directly**; it trusts JWT claims and calls the control plane only for
directory/provisioning operations it cannot derive from the token.

```
                         ┌──────────────────────────────┐
                         │   Control Plane (quantum-     │
   issues JWT  ◀─────────│   system)  — own DB, own      │
   (tenantId, realm,     │   lifecycle, own endpoints    │
    orgRefName, roles)   │                               │
                         │  Realm catalog · Credentials  │
                         │  Users · Orgs · Accounts      │
                         │  Memberships · Principal       │
                         │  policies · Invitations        │
                         └──────────────┬────────────────┘
                                        │  SystemDirectory API
                                        │  (provision realm, verify health,
                                        │   resolve principal policy)
                         ┌──────────────▼────────────────┐
                         │  Data Plane (realm server,     │
   request + JWT  ──────▶│  e.g. the tenant-plane app)         │
                         │  routes by JWT claims, applies │
                         │  RuleContext, per-realm DB     │
                         └────────────────────────────────┘
```

## 4. The `SystemDirectory` Abstraction

The central refactor. Introduce an interface that answers "where do cross-realm
concerns live, and how do I reach them" — replacing every direct
`getSystemRealm()` + repo call.

```java
public interface SystemDirectory {
    // realm catalog
    Optional<Realm> findRealmByEmailDomain(String emailDomain);
    Optional<Realm> findRealmByRefName(String refName);
    Realm registerRealm(Realm realm);

    // identity (unified principal — see §6; CredentialUserIdPassword is the
    // current persistence type, to be generalized)
    Optional<Principal> findPrincipalBySubject(String subject);
    Optional<Principal> findPrincipalByUserId(String userId);
    Principal savePrincipal(Principal principal);

    // principal authorization (effective roles/policies for a principal in a realm/org)
    PrincipalAuthorization resolveAuthorization(String subject, String realmRef, String orgRefName);
}
```

Two implementations:

- `LocalSystemDirectory` — resolves to a system-realm database via the existing
  `RealmRepo` / `CredentialRepo`, using a **configured** system-realm reference
  (default `system-com`). This is today's behavior, made explicit and
  configurable. Used for single-deployment installs and the test suite.
- `RemoteSystemDirectory` — calls the control-plane service over HTTP. Used when
  realm servers are deployed separately from the control plane.

Selection follows the app-facing deployment mode (the property WP3 documents):

```properties
quantum.mode=embedded                    # default — today's behavior
# quantum.mode=remote                    # split planes (tier 2)
# quantum.system-service.base-url=...    # required in remote mode
# quantum.system.directory.mode=local    # optional override for the directory
#                                        # alone (migration windows: app remote,
#                                        # directory still local)
```

`QuantumModeConfig` (quantum-system) is the single source of the mode;
`SystemDirectoryProducer` derives the directory implementation from it
(embedded → local, remote → control-plane client, fail-loud until Phase C).

What changes at the call sites: `TenantProvisioningService`, `RealmRepo`
look-ups for the catalog, credential reads during auth bootstrap, and
`EnvConfigUtils.getSystemRealm()` usages route through `SystemDirectory` instead
of passing a constant realm. The constant becomes the *default value of a config
property* read by `LocalSystemDirectory`, not something other classes reference.

## 5. Entity Ownership Map

| Entity | Today | Target owner | Notes |
|--------|-------|--------------|-------|
| `Realm` | system realm DB (hard-coded) | Control plane | reached via `SystemDirectory` |
| `CredentialUserIdPassword` + `UserProfile` identity bits | credential in system realm; profile per-realm | Control plane, **collapsed into `Principal`** | one generic identity (credential + name/email/phone); see §6 |
| Preferences (locale, currency, theme, units, tz — from `UserProfile`) | per-realm, largely unused | Control plane global default; scoped overrides may be realm-local | **separate `Preferences` object**, scoped GLOBAL/REALM/ORG/APP (see §6) |
| `Organization` | per-realm, referenced by `DomainContext` | Control plane | global |
| `Account` | per-realm | Control plane | maps to realm(s) |
| `Policy` (principalType=ROLE/USER) | per-realm | Control plane resolves principal→roles; realm keeps resource rules | see §6 |
| `Rule` / resource policies | per-realm | Data plane | unchanged |
| `DataDomain` (row-level) | embedded per entity | Data plane | unchanged |
| Invitations | n/a | Control plane | new, enables GitHub-style flow |

## 6. Unified Identity (`Principal`) + GitHub-Style Membership

### 6.1 Collapse Credential and UserProfile into one generic `Principal`

Today identity is double-modeled and the split has caused recurring confusion:

- `CredentialUserIdPassword` is *already* generic — `CredentialType` =
  `PASSWORD | SERVICE_TOKEN | API_KEY | OAUTH` — so a credential can be a human
  login, an MCP/service token, an API key, or an external-provider identity.
- `UserProfile` separately holds the human-only bits (`fname`, `lname`,
  `email`, `phoneNumber`, locale/units/currency/timezone) and points at
  credentials via `credentialUserIdPasswordRef` + `additionalCredentialRefs`.
- "Human is responsible" is *also* already half-expressed twice:
  `CredentialUserIdPassword.emailOfResponsibleParty` (free text) and
  `parentCredentialSubject` ("the subject of the owning PASSWORD credential" for
  a SERVICE_TOKEN).

Separate three concerns that today are tangled across `CredentialUserIdPassword`
and `UserProfile`: **credential/identity**, **identity attributes**, and
**preferences**. Collapse the first two into one generic `Principal`; keep
preferences as their own object.

```
Principal                          // the authenticatable thing (control plane)
  subject            // stable unique id (existing key)
  type               // HUMAN | SERVICE | MACHINE | API_KEY | OAUTH  (from CredentialType)
  credential         // password hash / token material / external ref (optional per type)
  roles[]            // default/global roles
  identity?          // for HUMAN: display name, email, phone — *who* this is
  responsibleParty   // REQUIRED reference to a HUMAN Principal accountable for this one
  memberships        // see 6.2

Preferences                        // *how a human likes things* — NOT on the credential
  principalRef       // owning principal
  scope              // GLOBAL (default) | REALM:<ref> | ORG:<ref> | APP:<key>
  locale / defaultLanguage
  defaultUnits
  defaultCurrency
  defaultTimezone
  theme              // and other UI settings
```

Key invariant: **a credential is not always a human, but a human is always
responsible for it.** `responsibleParty` is a required, *typed* reference to a
HUMAN principal. For a human principal it is self (or another accountable
human); for SERVICE/MACHINE/API_KEY principals it must resolve to a human.
This generalizes and replaces the two ad-hoc fields (`emailOfResponsibleParty`,
`parentCredentialSubject`) and supersedes `UserProfile.additionalCredentialRefs`
(a human's "additional credentials" become the set of principals whose
`responsibleParty` points at that human).

Why `Preferences` is separate, not a field on `Principal`:

- It is a different lifecycle and concern — locale/currency/theme/units/timezone
  describe *how a human likes the UI*, not identity or authn material. Putting
  them on the credential is the kind of mixing that caused the original
  confusion. (These fields exist on `UserProfile` today but are largely unused —
  a clean point to relocate rather than migrate behavior.)
- It is naturally **scoped**: a human may want a different theme per app, or a
  currency/locale that follows the org/realm they are acting in. `scope` lets a
  GLOBAL default be overridden per REALM / ORG / APP, resolved at request time
  (most-specific wins). Only `Preferences` needs this scoping — identity and
  credential stay global.
- Service/machine principals simply have no `Preferences`.

This keeps machine/service identities first-class (own subject, own roles, own
token lifecycle) while guaranteeing every one of them traces to an accountable
human — the GitHub "a bot/deploy key/PAT is always owned by a user or org"
property.

### 6.2 Membership

Promote `authorizedRealms` into first-class membership.

```
User (global identity; subject is the stable key)
  └─< Membership >─ Organization        (many-to-many)
                      roles: [..]        (org-scoped roles)
                      policies: [..]     (org-scoped principal policies)
Organization ─< Account ─→ Realm(s)      (account maps to one or more realms)
```

- A `User` is global (one identity, many orgs).
- A `Membership` ties a user to an org with org-specific roles/policies.
- `Account` within an org maps to realm(s) (shared or dedicated).
- The control plane resolves *effective* roles/policies for `(subject, realm,
  org)` and projects them into the JWT. The data plane's `RuleContext` continues
  to evaluate **resource** rules per realm; it consumes principal roles from the
  token rather than reading principal policies from the system realm.

Invitation flow: control plane creates an invitation (email + org + roles) →
invitee accepts → `Membership` created → subsequent tokens carry the org/roles.

## 7. JWT Claims Contract

The token is the boundary. Claims: `subject`, `tenantId`, `realm`,
`orgRefName`, `roles` (plus standard OIDC). The data plane self-routes from
`realm`/`orgRefName` and never calls back to resolve identity. This is the
producer side of the contract already drafted on the consumer side in
`the system-to-tenant OpenAPI contract`; the control plane needs a
complementary identity/org API (token issuance, org/user/account CRUD,
invitations, principal-authorization resolution). Issuance is `quantum-oauth-server`
+ `quantum-jwt-provider` (see §3). On the data-plane side, `IdentityAssembler`
(`assemble(ProviderClaims)` / `assemble(subject, roles, attrs)`) already converts
claims into a `SecurityIdentity`, so the consumer bridge exists — it just reads
roles/membership from the token instead of the system realm.

## 8. Deployment & Lifecycle

- Control plane: independent deployment, own system database, own config (IdP,
  secrets via `quantum-secrets`/Vault), HA, conservative release cadence
  (security-critical). One shared instance across all tenants.
- Data-plane realm servers: scale per tenant/realm; shared (realm-routed) or
  dedicated per enterprise tenant; faster release cadence.
- a multi-tenant platform: `the system-plane app` becomes a *deployment of the control plane*, not a
  bespoke app. `the tenant-plane app` is a data-plane realm server in remote mode.

## 9. Phased, Backward-Compatible Migration

Phase A — Introduce indirection, no behavior change.
- Add `SystemDirectory` + `LocalSystemDirectory`.
- Make the system realm a config property (default `system-com`); route
  `getSystemRealm()` usages and the catalog/credential calls in
  `TenantProvisioningService` through `SystemDirectory`.
- Exit test: full framework suite passes; flipping the config property proves
  the system-realm pointer is configurable (no hard-coding). **This is the first
  locally testable milestone.**

Phase B — Extract control-plane module.
- New `quantum-system` module owning `Realm`, `CredentialUserIdPassword`,
  identity, `Organization`, `Account`, principal `Policy`, `Membership`,
  invitations; move `TenantProvisioningService` + `UserManagement` impls here.
- Stand up `quantum-system` as a deployable using `quantum-oauth-server` /
  `quantum-jwt-provider` for token issuance.

Phase B progress (2026-06-10, branch the integration branch):
- (1/n) `quantum-system` module created; `SystemDirectory` contract +
  local impl/producer relocated into it (FQNs of the contract unchanged);
  `RealmCatalogService` + metering contracts (`MeteringEvent`/`MeteringSink`)
  added; quantum-framework depends on it so app classpaths are unchanged.
- (2/n) `TenantProvisioningService` realm-catalog operations routed through
  `RealmCatalogService`; `SystemDirectory` retained only for the credential
  lookup (identity seam lands in Phase D).
- (3/n) Mode seam: `quantum.mode` (embedded default) via `QuantumModeConfig` +
  `SystemRealmOwnership`; in remote mode `FrameworkStartupCoordinator` skips
  system-realm migrations and baseline identity, and the seed/bootstrap
  startup runners exclude the system realm from their realm lists while
  app-realm work proceeds locally (wp3 tier-2 semantics).
  `MigrationService` gained `initializeStartupRealms(boolean includeSystemRealm)`
  (existing no-arg method unchanged) so it stays mode-unaware.
- (4/n) Admin REST surface behind one build-time switch:
  `quantum.system-rest.enabled` (default true via `enableIfMissing` —
  classpaths and registration unchanged for every existing app). Setting it
  false removes the control-plane admin resources (`/admin/tenants*`,
  `/security/realm*`, `/security/realm-memberships`, `/admin/seeds`,
  `/system/migration`, `/admin/bootstrap-packs`, `/onboarding/*`) via
  `@IfBuildProperty` — superseding the per-type `quarkus.arc.exclude-types`
  pattern for tier-1 hardening, and what a tier-2 tenant-plane app sets at
  build time.
- (6/n, `1.4.1-SNAPSHOT`) Physical `quantum-system-rest` extraction: the
  security and control-plane JAX-RS resources moved out of `quantum-framework`
  into an independently selectable, Jandex-indexed server module. The Java
  resource classes are grouped under the `.security` package, while every
  endpoint `@Path` value is unchanged. As the first compatibility slice,
  `quantum-system-rest` depends on `quantum-framework`; later slices move its
  service and persistence closure into narrower modules.
- (7/n, `1.4.1-SNAPSHOT`) Physical `quantum-ontology-rest` extraction: the
  ontology registry, graph, administration, and drift-repair JAX-RS resources
  moved out of `quantum-ontology-mongo` with packages and `@Path` values
  unchanged. `quantum-ontology-mongo` is now endpoint-free and no longer
  depends on `quantum-framework`; only the standalone
  `quantum-ontology-service` selects the REST module. The policy bridge now
  declares its REST-core dependency directly instead of receiving the full
  framework accidentally through the Mongo module.
- (8/n, `1.4.1-SNAPSHOT`) Shared `quantum-rest-core` extraction:
  `BaseResource`, CSV import/export, and their import-processing closure moved
  into a Jandex-indexed module containing no concrete application endpoint.
  `quantum-ontology-rest` and `quantum-ontology-policy-bridge` now use this
  module without depending on `quantum-framework`. The implementation lives at
  `com.e2eq.framework.rest.core.BaseResource`; `quantum-framework` retains the
  historical `com.e2eq.framework.rest.resources.BaseResource` as a source- and
  binary-compatible bridge for existing applications. Endpoint `@Path` values
  are unaffected. `quantum-system-rest` uses the new core directly but still
  depends on `quantum-framework` for provisioning, onboarding, policy-loading,
  and authentication services; extracting that service closure is the next
  slice.
- (9/n, `1.4.1-SNAPSHOT`) Endpoint-free system runtime extraction:
  `quantum-seed-core` now owns seed-pack loading and persistence,
  `quantum-system-management` owns authentication, tenant provisioning,
  onboarding, application admission, policy-file loading, and the associated
  shared REST payload/service packages, and `quantum-security-runtime` owns
  request authorization and security filters. Package ownership remains
  intact rather than being split across jars. `quantum-seed-s3` now depends on
  seed core directly. Most importantly, `quantum-system-rest` no longer
  depends on `quantum-framework`; it composes REST core, the endpoint-free
  management/security runtimes, and the JWT provider. The three new runtime
  artifacts contain no concrete JAX-RS resource classes, while all 19
  system-control-plane resources retain the `@Path` annotations established
  before the physical extraction.
- Workflow boundary review: the Enterprise workflow client is already split
  into models, client, and Quarkus integration jars. Its only local JAX-RS
  resource is `/workflow/resolvers/...`, an intentional runtime callback into
  application-owned settings, secrets, and credentials—not a duplicated
  workflow-engine API. It remains application-side; applications that do not
  provide this callback use `quantum-workflow-client` rather than
  `quantum-workflow-quarkus`.
- (10/n, `1.4.1-SNAPSHOT`) Authentication-host dependency closure:
  contract hashing and compatibility checks moved into the endpoint-free
  `quantum-contract-core`, so the Enterprise auth client provider no longer
  pulls `quantum-framework`, repositories, or server resources into consuming
  applications. `quantum-auth-service` now composes `quantum-system-rest`,
  `quantum-system-management`, `quantum-jwt-provider`, and the endpoint-free
  Enterprise `quantum-filesystem-core` directly. The filesystem REST adapter
  remains independently selectable and retains both existing endpoint paths.
  `quantum-camel` and `quantum-action-enablement-enterprise` also select the
  endpoint-free core because they consume only its managed-directory SPI and
  persistence models.
  The `Pair` query-parameter converter required by inherited
  `BaseResource` operations also moved into `quantum-rest-core`, completing
  that REST infrastructure closure without changing its package or behavior.
- (11/n, `1.4.1-SNAPSHOT`) Enterprise Camel framework decoupling:
  `quantum-camel` no longer depends on `quantum-framework`. Its eleven
  inherited CRUD resources now extend `quantum-rest-core` directly, while
  models, security-rule runtime, persistence, ontology ingest, and managed
  directory contracts resolve from their existing narrow owners. All Camel
  resource packages and `@Path` annotations are unchanged. The module still
  intentionally contains both its integration runtime and operational REST
  resources; a later physical `quantum-camel-core` / `quantum-camel-rest`
  split can make those independently selectable.
- (12/n, `1.4.1-SNAPSHOT`) Physical Enterprise Camel split:
  `quantum-camel-core` now owns the endpoint-free integration runtime,
  models, repositories, managed-directory and SFTP components, route
  lifecycle, transmission tracking, ontology ingest, extension SPI, runtime
  configuration, and tests. The historical `quantum-camel` coordinates are
  retained as the REST adapter and contain only the eleven operational
  resource classes plus a Jandex index. The adapter depends on core and
  `quantum-rest-core`; core depends on neither the adapter nor REST core.
  All resource packages and `@Path` values remain unchanged.
- (13/n, `1.4.1-SNAPSHOT`) Physical Enterprise workflow Quarkus split:
  `quantum-workflow-quarkus-core` now owns the endpoint-free CDI runtime-client
  producer, security-context mapper, and connector resolver SPI without
  repository dependencies. The optional `quantum-workflow-onboarding` owns the
  repository-backed tenant-onboarding bridge and work-item mapper.
  `quantum-workflow-resolver-rest` contains only the optional application-side
  connector resolver resource and depends on core, while the historical
  `quantum-workflow-quarkus` coordinates remain a compatibility bundle over
  all three slices. Its `/workflow/resolvers`
  root and all three callback subpaths are unchanged and pinned by a contract
  test. Applications that need CDI workflow integration without hosting
  callbacks select core directly. Duplicate onboarding request/response
  classes were removed from Enterprise in favor of their canonical
  `quantum-system-management` owners.
- (14/n, `1.4.1-SNAPSHOT`) Physical Enterprise job-runner split:
  `quantum-jobrunner-core` now owns the endpoint-free JobRunr scheduling and
  execution runtime, models, repositories, metrics, built-in `run-flow` type,
  and contribution SPI. `quantum-jobrunner-rest` owns only
  `JobAdminResource`; its `/system/jobrunner` root and all thirteen method
  paths are unchanged and pinned by a contract test. The historical
  `quantum-jobrunner` coordinates compose both slices for compatibility.
  `quantum-jobrunner-service` now hosts the REST adapter with the narrow
  security, JWT, model, and repository owners and no longer depends on
  `quantum-framework`. Missing tenant or run-as identity now fails fast
  instead of running a background job under system context.
- (15/n, `1.4.1-SNAPSHOT`) Physical Enterprise ontology-service split:
  `quantum-ontology-service-core` now owns the endpoint-free TBox admission,
  vocabulary governance, ontology read, policy-decision, DTO, and
  repository-facing implementation. `quantum-ontology-service-rest` owns only
  the four service-specific JAX-RS resources. The historical
  `quantum-ontology-service` coordinates remain the deployable Quarkus host,
  with runtime configuration, governance seed pack, deployment assets,
  integration tests, OpenAPI contract, and generated SDKs. Its eight
  service-specific HTTP method/path combinations are unchanged and pinned by
  a contract test; the host no longer depends on `quantum-framework`.
  Application backends consume the generated SDK and therefore expose none of
  these endpoints and pull in none of the ontology repositories.
- (16/n, `1.4.1-SNAPSHOT`) Physical Enterprise surveys split:
  `quantum-surveys-core` now owns the endpoint-free survey models,
  repositories, publishing, campaigns, respondent submissions, result
  aggregation, DTOs, and attachment storage SPI. `quantum-surveys-rest`
  owns only the three resource types. The historical `quantum-surveys`
  coordinates compose both slices for compatibility. Existing Java packages,
  the `/psa/survey-campaigns` and `/psa/survey-versions` roots, all thirteen
  campaign method paths, and the abstract `SurveyResource` extension paths
  are unchanged and pinned by a contract test. Core and REST use narrow
  `1.4.1-SNAPSHOT` framework owners and neither depends on
  `quantum-framework`.
- (17/n, `1.4.1-SNAPSHOT`) Physical Query Gateway REST split:
  `quantum-query-rest` now owns `QueryGatewayResource` as the opt-in HTTP
  adapter for generic Morphia query operations. Its Java package, `/api/query`
  root, and all twelve method/path combinations are unchanged and pinned by a
  contract test. The historical `quantum-framework` coordinates compose this
  module for compatibility, while `quantum-mcp-server` and the Enterprise
  `quantum-system-service` select it directly. The system service now depends
  on narrow REST, security, system, seed, secrets, repository, ontology, and
  MCP owners instead of `quantum-framework`; therefore it hosts its own
  control-plane resources and `/api/query` without also exposing the rest of
  the compatibility bundle.
- (18/n, `1.4.1-SNAPSHOT`) Physical seed administration REST split:
  `quantum-seed-core` remains the endpoint-free discovery, validation,
  persistence, and loading runtime, while `quantum-seed-rest` now solely owns
  `SeedAdminResource`. Its Java package, `/admin/seeds` root, and all eight
  method/path combinations are unchanged and pinned by a contract test.
  `quantum-system-service` selects the adapter explicitly because its
  generated SDK already publishes these operations. Neither
  `quantum-framework` nor `quantum-system-rest` includes the adapter at
  runtime, preventing application backends and `quantum-auth-service` from
  unintentionally hosting seed administration. The existing framework
  integration harness retains the adapter only in test scope.
- (19/n, `1.4.1-SNAPSHOT`) Physical migration administration REST split:
  `quantum-migration-rest` now solely owns `MigrationResource`, including
  database-version introspection, index administration, change-set execution,
  and migration SSE operations. Its Java package, `/system/migration` root,
  and all ten method/path combinations are unchanged and pinned by a contract
  test. `quantum-system-service` selects the adapter explicitly and publishes
  the operations through its generated contract/SDK seam. Neither
  `quantum-framework` nor `quantum-system-rest` includes the adapter at
  runtime, preventing application backends and `quantum-auth-service` from
  unintentionally hosting database administration. The existing framework
  integration harness retains the adapter only in test scope.
- (20/n, `1.4.1-SNAPSHOT`) Physical bootstrap-pack core/REST split:
  `quantum-bootstrap-core` now owns the endpoint-free models, contributor and
  handler SPI, execution runtime, run history, and startup infrastructure.
  `quantum-bootstrap-rest` solely owns `BootstrapPackAdminResource`. Its Java
  package, `/admin/bootstrap-packs` root, and all seven method/path
  combinations are unchanged and pinned by a contract test.
  `quantum-system-service` selects the REST adapter explicitly; product and
  application modules that contribute packs select only the core artifact.
  `quantum-framework` retains core startup behavior for compatibility but
  includes the REST adapter only in test scope, so application backends and
  `quantum-auth-service` no longer unintentionally host bootstrap-pack
  administration.
- (21/n, `1.4.1-SNAPSHOT`) Physical contract-identity REST split:
  `quantum-contract-core` remains the endpoint-free canonical hashing and
  compatibility runtime, while `quantum-contract-rest` now solely owns
  `ContractHashResource`. Its Java package and `GET /contract-hash` endpoint
  are unchanged and pinned by a contract test. The framework compatibility
  bundle retains the adapter because the endpoint describes the host's own
  generated contract rather than a shared control-plane API.
  `quantum-auth-service` selects it directly, restoring its required generated
  SDK handshake without pulling `quantum-framework` or unrelated repositories
  back into the service.
- (5/n) `TenantLifecycle` contract in quantum-system
  (`com.e2eq.framework.api.tenant`: `TenantLifecycle`,
  `TenantProvisionRequest/Result`, `TenantDeleteResult` — dependency-light,
  no Lombok). `TenantProvisioningService` implements it by delegation; its
  existing signatures and inner types are untouched (wp3 rule 2 — the contract
  mirrors, it does not replace). Consumers headed across the plane boundary
  (admin resources for the future quantum-system-rest jar, the system-plane app)
  inject the contract; Phase C adds the remote implementation against it.
- (B1 close-out decision, 2026-06-11): the original "move entities +
  UserManagement into quantum-system" relocation is superseded by the
  layering the Phase B/C increments established and is now the documented
  end-state: **entities stay in quantum-models** (shared persistence
  contracts every layer reads — moving them above the repos would invert the
  build graph), **control-plane behavior lives in quantum-system**
  (SystemDirectory, RealmCatalogService, TenantLifecycle,
  RealmMembershipService, mode seam, remote clients), and **identity
  provider implementations live in provider modules** (quantum-jwt-provider /
  quantum-oauth-server), not quantum-framework. The physical
  `quantum-system-rest` JAR subsequently landed on the `1.4.1-SNAPSHOT`
  modularization line. The `quantum.system-rest.enabled` switch remains for
  build-time selection within deployments that include the server module.
- Known remote-mode gap for Phase C: when seeding *app* realms,
  `SeedStartupRunner` still reads realm records and admin credentials from
  the local system-realm database. In a true split deployment those reads
  must route through `SystemDirectory` (remote) — this is exactly the Phase C
  client work, tracked here so it is not discovered in production.

Phase C — Remote mode.
- Add `RemoteSystemDirectory` (HTTP client to `quantum-system`).
- Add the control-plane identity/org API; align with the system↔tenant contract.
- Realm servers in remote mode resolve identity/roles from JWT + control plane.

Phase D — Identity collapse + membership model.
- Collapse `CredentialUserIdPassword` + `UserProfile` identity bits →
  `Principal`; extract locale/currency/theme/units/tz into a separate scoped
  `Preferences` object; introduce typed `responsibleParty` (backfill from
  `parentCredentialSubject` / `emailOfResponsibleParty`); keep
  `CredentialRepo`/`UserProfileRepo` callers working via a compatibility layer
  during the window.
- Migrate `authorizedRealms` → `Membership`; lift `AccessInviteService` to
  org-scoped; project org-scoped roles into JWTs.

Phase E — Data migration.
- Move credentials/realm catalog out of the `system-com` *data* realm into the
  control-plane system database; backfill memberships.

## 10. Affected Classes (initial)

- `quantum-models`: `Realm`, `DomainContext`, `CredentialUserIdPassword` +
  `CredentialType` + `UserProfile` (→ collapse into `Principal`; generalize
  `emailOfResponsibleParty`/`parentCredentialSubject` into typed
  `responsibleParty`), `UserGroup`, `Organization`, `Account`, `Policy`, `Rule`,
  `EnvConfigUtils` (`getSystemRealm`).
- `quantum-morphia-repos`: `RealmRepo`, `CredentialRepo`, `UserProfileRepo`,
  `PolicyRepo`, `RuleContext` (principal-role source), `MorphiaDataStoreWrapper`
  (unchanged, but realm selection now driven by JWT/`SystemDirectory`).
- `quantum-framework`: `TenantProvisioningService`,
  `TenantProvisioningResource`, `UserManagement` impls, `UserProfileResource`,
  `IdentityAssembler`, `AccessInviteService` / `AccessInviteProvisioner` (lift
  realm-scoped → org-scoped), `SecurityFilter` / auth bootstrap filters.
- Reuse as-is: `quantum-oauth-server`, `quantum-jwt-provider`.
- New: `quantum-system` module + `SystemDirectory` (+ local/remote impls).

## 11. Testing Strategy / Local Milestones

- Phase A: run the existing framework integration tests; add a test that sets a
  non-default system realm via config and asserts provisioning + credential
  storage follow the configured pointer (proves de-hard-coding). Runnable
  locally with the framework's existing Mongo test setup.
- Phase B/C: contract tests for the control-plane API; a docker-compose that
  runs `quantum-system` + one realm server + Mongo; e2e: issue token → realm
  server routes by claims → governed query.
- a multi-tenant platform ties in via `the platform compose overlay` once `the system-plane app`
  points at the control plane.

## 12. Risks / Open Questions

- Blast radius: every app resolves realm/credentials through these paths; Phase A
  must be a pure refactor with green tests before anything moves.
- Auth bootstrap ordering: credentials must be resolvable *before* realm
  selection — the control plane must be reachable at login. Define caching /
  failure modes for `RemoteSystemDirectory`.
- Identity collapse: merging `CredentialUserIdPassword` + `UserProfile`'s
  identity bits into `Principal` touches auth, seeds, and every
  `UserProfileRepo`/`CredentialRepo` caller. Plan a compatibility window (keep
  both repos backed by the unified store, or a view) rather than a flag-day
  rename.
- Preferences placement: the GLOBAL default clearly belongs to the control
  plane, but decide where *scoped* overrides physically live — control plane
  (one store, scope as a field) vs. data plane (realm-local prefs travel with the
  realm). Recommendation: control plane with a `scope` field for v1 (simpler,
  one read path); revisit only if realm-local theming/branding needs it.
- `responsibleParty` enforcement: needs a typed reference + validation that it
  resolves to a HUMAN principal; backfill from existing
  `parentCredentialSubject` / `emailOfResponsibleParty`; define what happens when
  a responsible human is deactivated (reassign vs. cascade-disable credentials).
- Policy resolution boundary: confirm `RuleContext` only needs principal *roles*
  from the token and that all *resource* rules remain realm-local.
- Migration of existing `system-com` data: one-time backfill + dual-read window.
