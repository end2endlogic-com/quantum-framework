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
`actingService { userId, subject }`. Refusals: `DELEGATION_NOT_ALLOWED` (403),
`DELEGATION_TARGET_UNKNOWN` (403).

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
4. **Slim tokens**: stop minting the removed claims once no deployed receiver
   reads them; then delete the delegated-claims path,
   `ApplicationTokenAdmissionFilter`, foreign-application role re-derivation
   and token tenant selection.

Applications still on 1.4.2 keep working during steps 1-3 because tokens keep
their claims until step 4.

### App flip checklist (step 2)

An application flips to the remote principal source with configuration only; no
app-local validator, audience profile split or claim reader is needed. The
framework (`RemotePrincipalSettings`) refuses at startup, with a typed
`PrincipalSourceConfigurationException` code, any configuration that would bring
back token claims or audience admission.

Dependencies: framework and enterprise 1.4.3-SNAPSHOT or later, and
`quantum-auth-client-provider` (the `RemotePrincipalSource`).

Set (unprofiled, or in every deployed profile such as `%prod`):

```properties
auth.provider=quantum-auth-service
quantum.auth-service.base-url=${QUANTUM_AUTH_SERVICE_URL}
mp.jwt.verify.issuer=${HELIXORQ_ISSUER}
mp.jwt.verify.publickey.location=${HELIXORQ_JWKS_URL}
quantum.security.session.application-id=<the application id granted at Auth>
quantum.auth.principal-source=remote
```

Remove from every profile that runs remote mode, and from the deployment's
environment:

- `quantum.auth.trust-token-claims` (`QUANTUM_AUTH_TRUST_TOKEN_CLAIMS`)
- `quantum.auth.expected-audience` (`QUANTUM_AUTH_EXPECTED_AUDIENCE`)
- `mp.jwt.verify.audiences` (`MP_JWT_VERIFY_AUDIENCES`). A `%dev,test` profile
  that still builds the principal locally may keep it there.
- `quantum.security.session.realm` and `quantum.security.session.selection-policy`
- any `QUANTUM_AUTH_PRINCIPAL_SOURCE` override
- `quarkus.http.auth.proactive=false` (the default, `true`, is required; it is a
  build-time property, so an environment variable cannot change it)

Grant the identities (users and service credentials) the application at Auth.
Issuer and signature are still verified locally; the application gate is Auth's
`APPLICATION_NOT_GRANTED` (403) on resolve.

| Startup refusal | Cause |
|---|---|
| `PRINCIPAL_SOURCE_INVALID` | `quantum.auth.principal-source` is neither `remote` nor unset |
| `PRINCIPAL_SOURCE_UNAVAILABLE` | no (or more than one) `RemotePrincipalSource` bean |
| `PRINCIPAL_SOURCE_APPLICATION_MISSING` | no `quantum.security.session.application-id` (or `quantum.application-registration.application-id`) |
| `PRINCIPAL_SOURCE_SESSION_REALM` | an application-session realm or selection policy is set |
| `PRINCIPAL_SOURCE_TRUSTS_TOKEN_CLAIMS` | `quantum.auth.trust-token-claims=true` |
| `PRINCIPAL_SOURCE_AUDIENCE_CONFIGURED` | `quantum.auth.expected-audience` or `mp.jwt.verify.audiences` is set |
| `PRINCIPAL_SOURCE_LAZY_AUTHENTICATION` | `quarkus.http.auth.proactive=false` |
| `PRINCIPAL_SOURCE_NOT_REMOTE` | a deployment (not dev or test) whose configuration declares `remote` for its profile, unset by a higher-priority source such as an environment variable |

Verify after the flip: a validly signed token with no `aud`, or another
application's `aud`, reaches resolve (403 `APPLICATION_NOT_GRANTED` when not
granted, 200 when granted); a wrong issuer or signature is 401 and never reaches
Auth; Auth unreachable is 503 `PRINCIPAL_RESOLUTION_UNAVAILABLE`.

Code that pairs `SecurityIdentity.getRoles()` with a realm reads
`PrincipalIdentityAttributes.rolesRealm(identity)` (the realm Auth resolved in
remote mode), and code that needs the application reads the resolved
`PrincipalContext` application, then `ReceivingApplication` (the configured
`quantum.security.session.application-id`). Only an application that configures no
id still falls back to the identity's `azp` there, until step 4; set the id when
flipping.

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

### Step 4: the slim-token switch

`quantum.auth.token-contract` on the issuer (`quantum-auth-service`, or an
embedded application that mints its own tokens) chooses what a user access
token carries. Refresh tokens are read only by the issuer and do not change.

| Value | Access token |
|---|---|
| `v1` (default; unset) | today's claims-bearing token |
| `v2` | `iss`, `sub`, `userId`, `iat`, `exp`, a random `jti`, `token_type=access`, `scope=authToken`, and `auth_time` when known |

`scope` tells an access token from a refresh token (`validateAccessToken` and
several receivers check it), as for service tokens v2. `auth_time` is
authentication metadata, not authorization. Neither is a removed claim. Any
other value refuses startup (`TOKEN_CONTRACT_INVALID`).

How each kind of receiver treats a v2 token:

| Receiver | v2 token |
|---|---|
| `principal-source=remote` (Quarkus) or Python `RemotePrincipalSource` | resolved by Auth. Only `iss` and `sub` are read |
| local credential store, `trust-token-claims=false` (Auth itself, embedded apps) | resolved in `IDENTITY` mode for `quantum.security.session.application-id`, exactly as Auth's resolve endpoint (`SecurityFilter` and `RolesAugmentor`): credential status, bindings, realm access, tenant selection and impersonation rules apply. No application id: **401 `TOKEN_CONTRACT_V2_UNRESOLVED`** |
| `trust-token-claims=true` (delegated-claims path) | **401 `TOKEN_CONTRACT_V2_UNRESOLVED`**, fail closed |
| `ApplicationTokenAdmissionFilter` / `quantum.auth.expected-audience` | refused: no `aud`/`azp` |
| `mp.jwt.verify.audiences` set, Python `HELIXOR_AUTH_AUDIENCE` set | refused at signature verification: no `aud` |
| Python `claims_to_principal`, TS session readers that decode the token | no roles, realm or tenant; refused or wrong |

Two behaviour changes for clients:

- A user who signs in to a realm other than the credential's home realm must
  send `X-Realm`. The token no longer names it. The sign-in response still
  returns `realm` and `activeApplication`.
- In a `MULTI_TENANT` realm other than the home realm, `X-Tenant-Id` selects
  the tenant. This is exactly what remote mode already does.

#### Flip checklist (before `quantum.auth.token-contract=v2` at Auth)

Every receiver of that Auth's tokens, in that environment, must be deployed
with all of the following:

1. Every Quarkus receiver runs `quantum.auth.principal-source=remote`, or
   resolves from its own credential store with `trust-token-claims=false`, on a
   framework build that contains this change. Nothing sets
   `trust-token-claims=true`, `expected-audience` or `mp.jwt.verify.audiences`
   in a deployed profile. `RemotePrincipalSettings` already refuses those
   settings in remote mode. A receiver on the local path also keeps proactive
   authentication (the default), as remote mode requires. With it,
   `IdentityOnlyAccessRefusalFilter` answers a refused v2 token with its typed
   code (401 `PRINCIPAL_DISABLED`, 403 `APPLICATION_NOT_GRANTED`, 503
   `PRINCIPAL_RESOLUTION_UNAVAILABLE`, ...) before `@RolesAllowed`.
2. Every Python receiver runs `HELIXOR_AUTH_PRINCIPAL_SOURCE=remote` and sets
   no `HELIXOR_AUTH_AUDIENCE` or `HELIXOR_EMPLOYEE_AUTH_AUDIENCE`. No service
   calls `claims_to_principal` directly.
3. Every front end and BFF takes roles, realm and tenant from the sign-in
   response or a resolve call, never by decoding the access token.
4. Clients send `X-Realm` (and `X-Tenant-Id` where needed) for any realm that
   is not the identity's default.
5. Auth itself receives its own tokens: every remote receiver calls
   `/v1/auth/principal-context/resolve` with the caller's bearer. Auth runs the
   local path, so it needs a `quantum.security.session.application-id` that
   every identity is granted, for example by a realm-wide default grant.
   Otherwise every resolve call, and so every remote receiver, refuses v2
   tokens. **Owner decision:** use a granted Auth application id, or resolve
   Auth's own self-service endpoints without application admission.

Verify after the flip, per receiver: a v2 token gets 200 where granted and 403
`APPLICATION_NOT_GRANTED` where not. Roll back by unsetting the property. The
next refresh then mints v1 again.

#### Deleting the claims path (gated)

The code below stays until the conditions hold, because a framework build
without it breaks every application still configured for it:

| Delete | Safe when |
|---|---|
| `SecurityFilter.buildDelegatedClaimsContext`, `trust-token-claims`, `delegatedRolesAfterRealmSwitch`, token tenant selection | no deployed application on this framework line sets `trust-token-claims=true` (checklist 1) |
| `ApplicationTokenAdmissionFilter`, `expected-audience` (quantum-auth-client-provider) | no deployed application sets `expected-audience` |
| `RolesAugmentor` groups/azp role derivation, `SessionSelectionCoordinator` azp/realm reads | v2 has been on in every environment for one access-token lifetime, and every role-bearing (v1) service token has been reissued as v2 and revoked (step 3) |
| v1 mint (`TokenUtils.generateUserToken` claims overloads, `CustomTokenAuthProvider` claim copies, OAuth userinfo `groups`) | the same, and v2 is the default |
| `azp` fallbacks (`RepoSecurityContextResolver`, `PermissionActionDependencyResolver`) | every application configures `quantum.security.session.application-id` |

Each deletion lowers the matching `security/token-claim-readers.allowlist`
counts.

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
