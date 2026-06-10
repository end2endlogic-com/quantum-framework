# Control Plane / Data Plane Split — System Realm as a Deployable Service

Status: Draft
Owner: (assign)
Related: `TenantProvisioningService`, `UserManagement`, `Realm`, `RuleContext`,
`quantum-oauth-server`, `quantum-jwt-provider`
Consumers: HelixorQ (`helixor-q/backend/helixorq-system`, `helixor-q/backend/helixorq-tenant`)

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

Data plane — the realm-scoped server (what `helixorq-tenant` is). Owns ontology,
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
   request + JWT  ──────▶│  e.g. helixorq-tenant)         │
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

Selection via config, e.g. `quantum.system.directory.mode=local|remote` and
`quantum.system.directory.url=...`.

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
`helixor-q/contracts/system-to-tenant.openapi.yaml`; the control plane needs a
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
- HelixorQ: `helixorq-system` becomes a *deployment of the control plane*, not a
  bespoke app. `helixorq-tenant` is a data-plane realm server in remote mode.

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
- HelixorQ ties in via `docker-compose.helixorq.yml` once `helixorq-system`
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
