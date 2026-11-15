# Identity-Only Tokens: Auth Resolves The Principal

Status: **Accepted** (2026-11-12, owner). All decisions below accepted.
Owners: quantum-framework (`quantum-security-runtime`, `quantum-jwt-provider`,
`quantum-morphia-repos`, `quantum-system-management`), quantum-enterprise
(`quantum-auth-service`, `quantum-auth-client-provider`), helixor-core (Python
receivers).

## Why

A token should say one thing: who the caller is. It is trusted because it is
signed. Everything else — roles, tenant, data filters, realm and impersonation
rights — is data on the credential record and in the grants and domain
policies, resolved for the application the caller is using. That was how the
framework worked through 1.4.1-SNAPSHOT for applications holding the
credential store, and it still works that way today when
`quantum.auth.trust-token-claims=false`.

What broke it:

| Commit | Date | Change |
|---|---|---|
| `29435680` | 2026-06-14 | DomainContext (realm, tenant, org, account) projected into issued tokens |
| `d459d3fd` (#59) | 2026-07-01 | `trust-token-claims` delegated path, then only a fallback when no local credential exists |
| `f95306d3`, `ab3ad050`, `bfafa353` | 2026-07-14..08-11 | `aud`/`azp` application tokens and admission |
| `1546463f` | 2026-07-21 | `realmRegEx` boundary claim |
| `b17cdd69` (#81) | 2026-08-02 | **claims first**: a delegating app builds the principal from token claims before (instead of) any credential lookup |
| `0278b479`, `e9bd632d` | 2026-08-18, 10-11 | tenant selection taken from the token |
| `5505f514`, `e1a6a6bf`, `bec969c9`.. | 2026-11-04/05 | session application/realm and foreign-app role re-derivation to patch the result |

Applications that delegate to `quantum-auth-service` have no credential store,
so since `b17cdd69` they take roles from `groups`, the tenant from
`tenantId`/`orgRefName`/`accountNum`, and the realm boundary from
`realmRegEx`. Consequences:

- A token minted in another realm, for another application, or before a grant
  changed gives the wrong roles or a 403 until the user signs in again.
- `area2RealmOverrides`, the credential's `dataDomainPolicy` and
  `authorizedRealms` never apply in delegating applications
  (`SecurityFilter.buildDelegatedClaimsContext` sets none of them).
- Every new case (launch pad handoff, another app's token, service tokens)
  added another claim or admission rule, and another way to fail.

## The design

### 1. Token contract v2 — identity only

User access token claims: `iss`, `sub`, `userId`, `iat`, `exp`, `jti`,
`token_type=access`.
Service token claims: `iss`, `sub`, `userId`, `iat`, `jti`, `token_type=service`
(no `exp`).

No `groups`, `realm`, `tenantId`, `orgRefName`, `accountNum`, `aud`, `azp`,
`realmRegEx`. A receiver verifies the signature against the issuer's JWKS and
reads nothing else. User access tokens still expire as a login session does;
service tokens do not.

### 2. Resolution contract — Auth resolves (identity, application)

New `quantum-auth-service` operation, authenticated by the caller's own bearer
token (no admin role needed; Auth resolves the identity that token proves):

```
POST /v1/auth/principal-context/resolve
{
  "applicationId": "acme-orders",          // required: the receiving application
  "realm": "acme-D1",                      // optional: the caller's X-Realm request
  "tenantId": "acme",                      // optional: the caller's X-Tenant-Id request
  "impersonate": { "userId": "..." },      // optional: X-Impersonate-UserId | -Subject
  "actingOnBehalfOf": { "userId": "..." }  // optional: delegating service tokens only
}
```

Response `helixor.principal_context.v1` (`PrincipalContextResolution`):

```
{
  "userId", "subject", "scope": "AUTHENTICATED|SERVICE", "applicationId",
  "realm", "realmOverrideActive",
  "domainContext": { "tenantId", "orgRefName", "accountId", "defaultRealm", "dataSegment" },
  "roles": [...],
  "area2RealmOverrides": { "<area>": "<realm>" },
  "dataDomainPolicy": { ... },
  "impersonatedBy": { "userId", "subject" } | null,
  "actingService": { "userId", "subject" } | null,
  "cacheSeconds": 60
}
```

Typed refusals: `PRINCIPAL_UNKNOWN` (401), `PRINCIPAL_DISABLED` (401),
`APPLICATION_NOT_GRANTED` (403), `REALM_NOT_AUTHORIZED` (403),
`TENANT_NOT_AUTHORIZED` (403), `TENANT_SELECTION_REQUIRED` (400), `IMPERSONATION_REFUSED` (403).

Auth computes it with exactly the 1.4.1 credential path, extracted into one
framework service (`PrincipalContextResolver`) and used by both
`SecurityFilter`'s local credential path (embedded mode) and this endpoint
(remote mode), so there is one implementation:

- credential by `sub` (then `userId`) in the system realm;
- realm: the requested realm checked by `validateRealmAccess`
  (`authorizedRealms`, `realmRegEx` on the credential), else the credential's
  `domainContext.defaultRealm`;
- roles: `IdentityRoleResolver.resolveEffectiveRoles` for (credential, realm,
  applicationId): credential roles + realm assignment roles + user-group roles,
  filtered by application grants;
- domain context: `resolveDomainContext` (home domain, or the target realm's
  catalog DomainContext); a requested tenant must be one the realm assignment
  authorizes;
- `area2RealmOverrides`, `dataDomainPolicy` from the credential;
- impersonation: `buildImpersonatedContext` with the credential's
  `authorizedImpersonateIds` / `impersonateFilterScript`.

### Tenancy models

The realm catalog's `RealmTenancyMode` decides; applications carry no code
for either.

- **Dedicated realm per tenant (`SINGLE_TENANT`)**: the realm's DomainContext
  is the one tenant. Resolving the realm resolves the database, and then you're
  done. `X-Tenant-Id` is not needed, and if sent it must match. Filtering, where
  policies ask for it, is by data segment or owner only.
- **Shared realm (`MULTI_TENANT`)**: the tenant is the identity's default
  tenant or the one chosen with `X-Tenant-Id`. It must be authorized by the assignment
  (`authorizedTenantIds` / `authorizedTenantRegEx`) and active in the realm.
  Policies scope every query to the tenant (plus org, account and segment).
- An identity with access to more than one tenant must define a default
  tenant, either in its credential (DomainContext `tenantId`) or in its
  data-domain policy. That default applies when no `X-Tenant-Id` is sent. An
  identity with exactly one tenant uses that tenant. Granting a second tenant
  to an identity with no default is refused when the grant is saved. An older
  record that breaks the rule fails resolution with
  `TENANT_SELECTION_REQUIRED` (400).

### 3. Receiving applications

`quantum-auth-client-provider` gains a remote principal source:

- `SecurityFilter` verifies the token, then asks the source for the principal
  context with the configured `quantum.security.session.application-id` and the
  request's `X-Realm`, `X-Tenant-Id`, `X-Impersonate-*`,
  `X-Acting-On-Behalf-Of-UserId`. Token claims beyond identity are ignored.
- Cache: bounded LRU keyed by (`iss`, `sub`, application, realm, tenant,
  impersonation), for `cacheSeconds` (default 60). A grant change or a
  revocation takes effect within that window, without a new token.
- Auth unreachable and no cached entry: fail closed with
  `PRINCIPAL_RESOLUTION_UNAVAILABLE` (503). Never fall back to token claims.

Application configuration becomes:

```properties
auth.provider=quantum-auth-service
quantum.auth-service.base-url=${QUANTUM_AUTH_SERVICE_URL}
mp.jwt.verify.issuer=${HELIXORQ_ISSUER}
mp.jwt.verify.publickey.location=${HELIXORQ_JWKS_URL}
quantum.security.session.application-id=acme-orders
quantum.auth.principal-source=remote
```

`quantum.auth.trust-token-claims`, `quantum.auth.expected-audience`,
`quantum.auth.audience-required` and `mp.jwt.verify.audiences` are no longer
used.

Helixor Python receivers (`helixor_core.auth` `require_principal`) do the same:
verify the signature, then resolve through the generated Python auth SDK with
their application id (`helixor-solver`, `helixor-decision`, ...).

### 4. Service tokens v2 — two kinds

A service is a credential like any other, and its token is identity only
(section 1). The credential records its kind (`serviceKind`).

- **Application token**: "I am this application; give me the answer." The
  credential has one realm and one tenant (its DomainContext) and its own grants
  per receiving service. Calls send only the bearer. Resolution is exactly as
  for a user: one realm, one tenant, the application's roles and policies.
  `X-Acting-On-Behalf-Of-*` is refused (`DELEGATION_NOT_ALLOWED`).
- **Delegating token**: for multi-tenant services (DI, Agent Framework). A call
  sends `X-Acting-On-Behalf-Of-UserId` (or `-Subject`). Auth checks that the
  service credential may delegate to that identity, then resolves the
  **acted-for identity**: its realm, tenant, roles, area overrides and
  data-domain policy, recording `actingService`. The receiving service applies
  those policies as that identity. Without the header the service runs as
  itself, like an application token.
- Delegation scope reuses the credential's impersonation authorization
  (`authorizedImpersonateIds`, `impersonateFilterScript`, `realmRegEx`).
- Roles for an acted-for user are resolved for the receiving service's
  application id, from the user's grants; realm-wide default grants keep that
  invisible to users.
- This changes `X-Acting-On-Behalf-Of-*`, which today only records who the
  work is for: with a delegating token it becomes the identity that is
  resolved. Callers stop naming the tenant with `X-Realm` / `X-Tenant-Id`.
- Issuing: `POST /security/service-token { description, realm, kind:
  "application" | "delegating" }` returns the token and its `credentialId`
  (`jti`). No roles, no expiry, no audiences, no identity-bound "own" kind.
  Revoking the credential stops it on the next resolution.
- Who may issue: an `admin` of the realm the service is created in.

Resolve request field: `actingOnBehalfOf { userId | subject }`. Response field:
`actingService { userId, subject }`. Refusal: `DELEGATION_NOT_ALLOWED` (403).

#### As built (step 3, auth contract 4.10.0)

- **One delegation refusal.** An unknown target and a target outside the
  delegation scope both answer `DELEGATION_NOT_ALLOWED`. `DELEGATION_TARGET_UNKNOWN`
  was folded into it so a delegating token cannot probe which identities exist.
  Resolver delegation was already in place from step 1 (`resolveDelegated`), so
  step 3 added no resolver code.
- **`exp` is fixed, not absent.** The JWT parser requires `exp`, so a service
  token carries `exp` = issue time + 100 years
  (`TokenUtils.SERVICE_IDENTITY_LIFETIME_SECONDS`). No caller chooses it, and
  revocation is by deactivating the credential. The claims are `iss`, `sub`,
  `userId`, `iat`, `exp`, `jti`, `scope` and `token_type: service`. `scope` stays
  `authToken` because the token validator requires it.
- **Issuer check.** The caller is the resolved principal: a realm admin acting as
  themselves, holding a user credential. Services, impersonated or delegated callers
  and external members are refused. The token is issued in the caller's resolved
  realm (select another with `X-Realm`). A delegating credential's scope is the
  whole of its one realm (`impersonateFilterScript` `true`, bounded by
  `authorizedRealms`).
- **Retired fields refused by name.** The 4.x request fields `roles`,
  `expirationSeconds`, `audiences`, `authorizedTenantIds`, `userId` and
  `applicationRef` answer 400 `SERVICE_TOKEN_FIELD_RETIRED`. They are not ignored,
  because an old caller would otherwise receive a token with less than it asked for.
- **Embedded path.** `SecurityFilter` recognises a v2 token from the token itself:
  `token_type: service` with a `jti` (role-bearing service tokens never carry one).
  It never takes a claims path. The service-claims path would run it in the system
  realm with the system data domain. In the application that holds the credential
  store (Auth) it resolves on the local credential path (home realm, tenant,
  grants), with no roles from the request identity. It is admitted only if its
  credential is present, active, records a `serviceKind` and matches `jti`;
  otherwise 401. Impersonation headers are refused, and acting-on-behalf-of headers
  are refused unless the kind is `DELEGATING`. A receiver without the credential, or
  one trusting token claims, refuses with 401 (it must run in remote mode).
  Role-bearing service tokens keep the old path until step 4.
- **Issuer authority is re-resolved.** Issuance re-resolves the caller's roles in the
  issuing realm with `IdentityRoleResolver`, without token roles: credential roles,
  the realm assignment and realm-wide (`*`) groups. It requires `admin` there and an
  active user credential. A sign-in token that still carries `admin` after the grant
  was revoked cannot issue, and an admin group of a single application does not make
  a realm admin.
- **Receivers that read roles from the token** (`trust-token-claims`, the 1.4.2
  claims path) get none from a v2 token and refuse. Give a service a v2 token only
  once the receivers it calls resolve through Auth.

## Rollout

1. **Resolver + endpoint** (framework 1.4.3, auth contract minor bump, additive):
   extract `PrincipalContextResolver`, add the endpoint and view, parity tests
   proving the endpoint returns what the 1.4.1 credential path built for the
   same inputs (realm switch, tenant selection, area overrides, impersonation,
   service identity).
2. **Remote principal source** in `quantum-auth-client-provider` and the
   Python SDK; flip applications one at a time (staff, launch pad, DI, Plan
   Compare, scheduler, Doc Audit, Solver, Decision, Agent Framework).
3. **Service tokens v2**: issue without roles, grant service identities on the
   Access page, reissue existing service tokens (Solver for big-box first).
   See "Step 3d: reissue runbook" below.
4. **Slim tokens**: stop minting the removed claims once no deployed receiver
   reads them; then delete the delegated-claims path,
   `ApplicationTokenAdmissionFilter`, foreign-application role re-derivation
   and token tenant selection.

Applications still on 1.4.2 keep working during steps 1-3 because tokens keep
their claims until step 4.

### Step 3d: reissue runbook (owner runs the token calls)

Prerequisites: framework 1.4.3 with the service-tokens-v2 change and Auth contract
4.10.0 are deployed to the environment. Deploying Auth 4.10.0 refuses every 4.x
role mint, so in dev the reasoning-backend API-key store (which mints at runtime)
fails until its step below is done. Do dev first, then prod.

Order: **Solver big-box, decision-service, DI, Agent Framework.** For each service:

1. Confirm every receiver the service calls resolves through Auth (resolver or
   remote mode). A receiver that still trusts token claims grants a v2 token
   nothing. Move that receiver first.
2. As the realm admin of the service's realm, issue the token:
   `POST /security/service-token` with `X-Realm: <realm>` and body
   `{"kind": "application", "description": "<service>"}`. Use
   `"kind": "delegating"` for services that act for users (DI, Agent Framework).
   Record the returned `userId` and `credentialId`. The response is shown once.
3. Grant that `userId` what the service needs on the realm's Access page, or with
   an application binding. Until then the resolver answers
   `APPLICATION_NOT_GRANTED`.
4. Store the token in the service's secret and roll the service.
5. Verify: `POST /v1/auth/principal-context/resolve` with the token and
   `{"applicationId": "<receiving app>"}` returns 200, the expected realm and tenant
   and `scope: SERVICE`. For a delegating token, also verify one `actingOnBehalfOf`
   call for a known user.
6. Deactivate the service's old 4.x credentials (`svc-*` credentials with no
   `serviceKind`).

**Decision-service and the reasoning API-key store.** reasoning-backend's key
store logs in and calls `POST /security/service-token` with roles at runtime.
In prod that answers 403 because decision-service lacks the mint role, which
causes the Reasoning 503. After v2 it uses its own issued application token
instead: no login and no mint. This needs a static application-token option in
`helixor-core` `ServiceTokenClient`, and the vault roles the store uses granted to
the token's `userId`. Once that ships, the 503 is fixed without granting
decision-service any mint permission.

**Jobrunner.** `quantum-jobrunner-service` deploy workers mint a per-run role
token. They stay pinned to the 4.8.0 SDK and fail closed against Auth 4.10.0
(`SERVICE_TOKEN_FIELD_RETIRED`) until they use one delegating token acting for
the run's `runAs` user.

Rollback: redeploy Auth 4.8.0. Tokens issued under 4.10.0 remain valid
identities with no roles. They grant only what was granted to their `userId`.

### Guard: no new claim readers

Until step 4 lands, nothing stops new code from reading the claims it removes.
`TokenClaimReaderGuardTest` (quantum-util) fails the build when main sources
read `groups`, `roles`, `realm`, `realmRegEx`, `tenant`, `tenantId`,
`orgRefName`, `accountNum`, `aud` or `azp` from a token, or call
`getClaim(<computed name>)`, more often than
`security/token-claim-readers.allowlist` permits. Each existing reader is
listed with its class (`resolver`, `issuer`, `remote-gated`, `must-move`,
`not-a-claim`) and the rollout step that removes it; counts only go down.
Applications run the same check from a test with
`TokenClaimReaderScan.assertNoNewReaders(root, allowlist)`.

## Decisions (accepted 2026-11-12)

1. Cache window of 60 seconds for grant changes and revocation to take effect.
2. Default tenant: an identity with more than one tenant defines it in its
   credential or data-domain policy; granting a second tenant without one is
   refused.
3. Issuing service tokens: realm `admin` instead of the
   `mint-own-service-token` / `mint-agnostic-service-token` roles, which are
   retired.
4. Two service token kinds (application, delegating); drop identity-bound
   ("own") tokens.
5. Delegation scope reuses the credential's impersonation authorization.
6. An acted-for user's roles come from their grants for the receiving service,
   plus realm-wide default grants.
7. Applications move to framework 1.4.3 for step 2 (an exception to staying on
   1.4.2).

## Superseded

- `USER_ACTOR_TOKEN_EXCHANGE_DESIGN.md` (already rejected).
- Application audience admission and `azp`-based role selection.
- Embedding roles and tenancy in tokens.
