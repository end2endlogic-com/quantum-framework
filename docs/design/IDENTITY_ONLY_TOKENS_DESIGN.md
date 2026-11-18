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

The principal source is independent of `auth.provider`.
`quantum.auth.principal-source=remote` needs only a `RemotePrincipalSource`
bean (`quantum-auth-client-provider` on the classpath) and
`quantum.auth-service.base-url`. `auth.provider` decides only where
authorization decisions run: `quantum-auth-service` sends them to Auth's
`/v1/authz/filter`, and `custom` keeps them in the application's own policies.
Billing runs `principal-source=remote` with `auth.provider=custom`, because
Auth's policy store holds no Billing policies. A blank
`quantum.auth-service.base-url` refuses startup with `PRINCIPAL_SOURCE_UNAVAILABLE`.

The resolved realm is the realm the request runs in. With no `X-Realm` it is
the identity's application binding marked `defaultRealm`, else the one realm
the application owns (`Realm.applicationRef`) in which the identity holds an
active assignment, else the identity's home realm. An application that owns
no realm, such as Billing, therefore sees every caller in its home realm. If
the receiver has not migrated that realm, `MigrationCheckRequestFilter`
answers 409 `REALM_NOT_MIGRATED`, with `realm` and `migration` (what the check
found) in the body. It never answers an untyped 500.

When the decision is delegated (`auth.provider=quantum-auth-service`), a
failed `/v1/authz/filter` call is typed. A 401 or 403 from Auth, for example a
revoked grant while the receiver still holds a cached principal, keeps Auth's
status and code (`AUTHORIZATION_REFUSED` when Auth's body has none). Auth
unreachable, or answering 5xx, gives 503 `PRINCIPAL_RESOLUTION_UNAVAILABLE`.
Other statuses are contract faults and stay 500 `AUTHORIZATION_CHECK_FAILED`.

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
  (`authorizedImpersonateIds`, `impersonateFilterScript`, `realmRegEx`). A
  script that cannot run is logged at ERROR and refused with the code for
  the call (`DELEGATION_NOT_ALLOWED` or `IMPERSONATION_REFUSED`). It is never
  read as the script answering `false`. One cause is a GraalJS engine that
  cannot write its resource cache: the Auth image sets `HOME=/tmp` and
  `-Dpolyglot.engine.userResourceCache`.
- The delegating service must itself be granted the receiving application.
  Its grant is read the way its own request would be admitted: its realm for
  the application, then its binding there, else the realm's ownership, the
  realm assignment's application grant or its credential pattern. Without that
  grant the answer is 403 `APPLICATION_NOT_GRANTED`, naming the service. The
  check runs before any acted-for identity is read. The delegated principal
  carries only the acted-for identity's roles, so without this check a
  receiver could not tell an admitted calling application from any other
  delegating credential.
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

#### As built (step 3, auth contract 4.11.0)

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
  otherwise 401. Impersonation headers are refused. Acting-on-behalf-of headers are
  refused for an `application` credential (403 `DELEGATION_NOT_ALLOWED`). For a
  `delegating` credential the local path resolves the delegation through the same
  validated path as `POST /v1/auth/principal-context/resolve`
  (`PrincipalContextResolver.resolveDelegated`: kind, delegation scope, realm in scope,
  the service's own grant for the application, the acted-for identity's grant), never
  as an unchecked marker. The evaluated principal is the acted-for identity, with the
  service recorded as `impersonatedBy*` and the `actingService` property, as the remote
  source presents it. The application is the receiving one. At an application-agnostic
  receiver (Auth), delegation is accepted only on an allowlisted decision operation: a resource
  method annotated `@DelegatedDecisionOperation`, today only `GET /v1/authz/filter`. The match
  is by resource method, never by path. There the application is the one the operation names
  in its `applicationId` query parameter, and a delegated request naming none is refused
  `DELEGATION_NOT_ALLOWED`. On every other Auth operation (lists, creates, grants,
  `/v1/auth/identity/current`) the acting header is refused `DELEGATION_NOT_ALLOWED`,
  whatever its query parameters. A delegating service therefore never acts for an identity on
  Auth's own data. Delegation always names its application: a delegated resolution without
  one is refused `DELEGATION_NOT_ALLOWED`. The service's own grant must also be explicit and
  active: an active binding, its active realm assignment's application grant, or its
  credential's application pattern. The realm being owned by the application is not a grant
  for a delegating service. Without `X-Realm` the realm is the delegation's realm
  (the acted-for identity's realm for that application), the same realm a remote
  receiver resolved, so a receiver forwarding the decision to Auth sends no sudo header
  unless its own caller sent one. Every caller of a decision operation, a person's
  signed-in token included, is resolved in identity mode there: roles, realm and tenant come
  only from Auth's store (memberships, assignments, bindings), never from the token's
  `groups`, `realm` or `tenantId` claims. A token re-signed with admin groups, or an admin
  demoted after sign-in, is decided with the store's roles at once. Without `X-Realm` the
  decision realm is the identity's realm for the named application, as the resolve operation
  picks it, not the realm the token was signed in for; without `X-Tenant-Id` the tenant is the
  identity's default tenant there. A receiver deciding through Auth refuses a decision made in
  any other realm than the one it evaluates (403 `AUTHZ_REALM_MISMATCH`). A receiver that names its application
  (`quantum.security.session.application-id`, else
  `quantum.application-registration.application-id`) resolves the token with an identity-mode
  request, the one the resolve operation builds: application admission (bindings, then the
  realm assignment's grant), realm and tenant selection and the `SERVICE` scope follow from the
  credential and its grants, and refusals are typed. Auth itself configures no application id.
  On Auth's own operations no application grant can apply, so a v2 token's roles there are its
  realm grants, as for a user's token. At Auth's decision operation a v2 token is always resolved
  in identity mode for the application the decision names (`applicationId`), acting header or
  not: an ungranted or unknown application, or none named, is 403 `APPLICATION_NOT_GRANTED`. It
  is never decided application-agnostic, which would carry every application's roles. The credential is read once, by the resolver's
  authentication lookup (`PrincipalContextResolver#credentialForSubject`); no principal exists
  yet, so no caller's rules can govern that read. A receiver without the credential, or
  one that names its application and trusts token claims, refuses with 401 (it must run in
  remote mode). Role-bearing service tokens keep the old path until step 4, except at a
  decision operation (below).
- **Decisions do not depend on `quantum.auth.trust-token-claims`.** Deployed Auth runs with
  the flag on. It changes neither the decision operation nor v2 admission at Auth:
  - Every verified bearer of a `@DelegatedDecisionOperation` method is resolved in identity
    mode, from the store, with the flag on or off.
  - Only the identity authority admits a v2 identity-only token (`jti == sub`) that names no
    receiving application, whatever the flag says, because its store is the one that resolves
    them. It is marked by the explicit setting `quantum.auth.identity-authority=true`, which only
    quantum-auth-service sets (fixed, not environment-driven). Any other receiver admits a v2
    token only when it names its application and does not trust token claims; a receiver that
    names no application is refused 401, never taken for Auth by that absence. Other Auth
    endpoints keep their claims-path behaviour under the flag until step 4.
  - `ServiceTokenV2TrustedClaimsApiTest` runs the whole `ServiceTokenV2ApiTest` suite with the
    flag on.
- **Legacy service tokens at a decision.** A role-bearing `token_type=service` token
  (no `jti == sub`) at a decision operation is resolved from the store like any caller, so its
  `groups` grant nothing. A subject the store does not know is refused 401
  `PRINCIPAL_UNKNOWN`. At a decision operation the acting-on-behalf-of headers are accepted only
  from a v2 `DELEGATING` credential. Anything else (a person's token, a legacy service token,
  an `application` credential) is refused 403 `DELEGATION_NOT_ALLOWED`. An unverified acting id
  is never echoed as `actingOnBehalfOf*`.
- **Data-domain parameters at a decision describe the resource.** `GET /v1/authz/filter`'s
  `tenantId`, `orgRefName`, `accountNumber`, `dataSegment`, `ownerId`, `locationId` and
  `businessTransactionId` are the decided resource's coordinates: receivers send their
  `ResourceContext` data domain.
  - They set only the resource context the rules see.
  - The principal's data domain is always the one resolved from the store; only `X-Tenant-Id`
    selects its tenant. A delegated decision is therefore the decision the receiver would make
    locally, where `RuleContext` matches rule bodies against the principal's own data domain.
  - A decision on a resource in another tenant is decided by the rules as the caller. Another
    tenant's tenant-scoped policy does not apply; a policy for any tenant (shared, system or
    support data) does.
  - Before, the parameters overwrote the principal's data domain, so a caller naming another
    tenant was evaluated as a member of that tenant.
- **Realm names are compared ignoring case.** An identity-mode resolution, and the roles
  augmentor's credential lookup, read a requested realm (`X-Realm`, `realm`) as its registered
  refName, compared ignoring case (`RealmRefNames`). A differently cased `X-Realm` is that realm.
  It no longer causes a MongoDB 13297 500. An unknown realm is refused typed
  (`REALM_NOT_AUTHORIZED`).
  - The catalog matches ignoring case (`LocalSystemDirectory.findRealmByRefName`: exact, then
    `RealmRepo.findByRefNameIgnoringCase`), so a mixed-case refName (`helixor-code-P1`) and
    a remote directory, which asks the same endpoint, are covered. Registration stays exact.
  - Lookups are cached, bounded (1024 names): a found name for 60 s, a missing one for 30 s, so
    an unknown `X-Realm` does not reach the directory on every request. `RealmRegistered`
    clears the cache.
  - `SecurityFilter` rewrites `X-Realm` to the registered refName before anything reads it, on
    every principal source (embedded and remote). Resources and repositories read the header
    as a database name, so a receiver in remote mode no longer answers 500 for another case.
    A receiver whose own catalog does not list the realm (a remote-mode receiver serving a
    realm Auth registers) takes the case from the principal Auth resolved for the request.
  - A directory outage is refused 503 `PRINCIPAL_RESOLUTION_UNAVAILABLE`, never a 500. The
    roles augmentor then drops token roles (fails closed).
- **Error responses carry no internals.** The framework's exception mappers no longer return
  stack traces (`debugMessage`) or an unmapped exception's own message. Those are logged server
  side. The development setting `quantum.rest.errors.include-debug-detail=true` restores them.
  This covers MongoDB write errors (a duplicate key is a fixed 400 conflict message, with no
  host, database, collection, index or key value, so it does not enumerate accounts), JSON
  processing errors and `QuotaExceededException`'s stack trace.
- **Issuer authority is re-resolved.** Issuance re-resolves the caller's roles in the
  issuing realm with `IdentityRoleResolver`, without token roles: credential roles,
  the realm assignment and realm-wide (`*`) groups. It requires `admin` there and an
  active user credential. A sign-in token that still carries `admin` after the grant
  was revoked cannot issue, and an admin group of a single application does not make
  a realm admin.
- **A delegating token needs a realm-wide admin.** A `delegating` credential acts for any
  identity of its realm, so its issuer's `admin` must hold across the realm. The role
  provenance (`IdentityRoleResolver.resolveRoleSources`, no token roles) decides:
  - The rule is invite management's rule for a realm administrator: every tenant of a dedicated
    realm, and in a shared realm only the tenants its own assignment authorizes.
  - Realm wide: a realm-wide (`*`) platform user group (no `ownerTenantId`;
    `IdentityRoleResolver.platformGroupRoles`) in any realm. Or `admin` from the credential or
    the realm assignment, in a dedicated realm, or in a shared realm when the active assignment
    authorizes every tenant (`authorizedTenantRegEx = "*"`).
  - A tenant-owned group never counts, whatever its application scope, so a tenant admin cannot
    make themselves realm-wide by creating one.
  - Tenant scoped: any other `admin` in a shared (multi-tenant) realm. Pooled provisioning gives
    a tenant's administrator flat credential roles (`admin`, `user`) and an assignment naming its
    one tenant, so a flat credential role alone is not realm wide there.
  - A tenant-scoped issuer is refused 403 `DELEGATION_ISSUE_REQUIRES_REALM_ADMIN`.
  - `application` tokens keep the realm-admin rule.
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

### Step 3d: reissue runbook (owner runs the token calls)

Prerequisites: framework 1.4.3 with the service-tokens-v2 change and Auth contract
4.11.0 are deployed to the environment. Do dev first, then prod.

**What keeps working.** Tokens already issued keep working after the deploy: a
role-bearing 4.x service token (its `jti`, when the signer adds one, is a random token
id, never its subject) stays on the
service-claims path, and an identity-bound 4.x token is a user token, until step 4
(`SecurityFilterServiceTokenV2Test`, quantum-enterprise `ServiceTokenV2ApiTest`).
**What stops.** Every runtime role mint: 4.11.0 answers it 400
`SERVICE_TOKEN_FIELD_RETIRED`, and a v2 token can only be issued once 4.11.0 runs.

**Before deploying Auth 4.11.0 (bridge, no downtime).** Move each runtime minter to a
static token while 4.10.0 (or 4.9.0) still mints:

- `helixor-core` `ServiceTokenClient` users (login + mint with roles): Helixor Code API
  and its state stores, employee-api, employee-workflow, influencer-api,
  helixor-reasoning-backend's API-key store, the solver-server billing reporter in prod.
  Each takes `HELIXOR_AUTH_SERVICE_TOKEN` from helixor-core 1.6.0 (callers on an older
  helixor-core move to 1.6.0 first). Mint one role token per service under 4.10.0 with the
  roles it mints today and an expiry that outlasts the reissue below (a static
  token is never refreshed), set it as that secret, remove the login variables, roll the service.
  The token stays valid after 4.11.0 and is replaced by a v2 token in the steps below.
- The Reasoning UX "mint API key" proxy and the tenant-admin-ui issue dialog send
  roles and an expiry: switch them to `{kind, description}` with 4.11.0.
- Scripts that mint role tokens (`rotate-local-service-token.mjs`,
  `mint-local-intake-token.mjs`, `prod-decision-identity.sh`,
  `reconcile_digitalworker_development.py`, the live-billing stack, employee
  `acceptance_scoped_auth.py`) and framework `ServiceTokenCLI` from before this change
  fail on their next run; update them to `{kind}` before rotating.

**Jobrunner** cannot bridge: its per-run mint is gone, and its v2 worker token is
refused by a receiver that trusts token claims. `quantum-system-service`
(`trust-token-claims=true` by default) must run the remote principal source first, by
the App flip checklist above (application id, no claims trust, proactive authentication
rebuilt, identities granted, one resolve verified). Then deploy Auth 4.11.0 and the jobrunner together, issue the delegating token
and grant it, and resume deployment runs; pause runs during that window.

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
instead: no login and no mint. `helixor-core` 1.6.0 `ServiceTokenClient` takes it
as `HELIXOR_AUTH_SERVICE_TOKEN` (HelixorAI/helixor-core#77), and the store switches in
HelixorAI/helixor-reasoning-backend#136. The vault roles the store uses are granted to
the token's `userId`. The 503 is then fixed without granting decision-service any
mint permission.

**Jobrunner.** `quantum-jobrunner-service` deploy workers present the jobrunner's
one delegating token (`QUANTUM_DEPLOY_SERVICE_TOKEN`, issued with
`{"kind":"delegating"}`) and act for the run's `runAs` user with
`X-Acting-On-Behalf-Of-UserId` (quantum-enterprise#237). Nothing is minted per run.
The 4.8.0 SDK pin is gone.

Rollback: redeploy Auth 4.10.0. Tokens issued under 4.11.0 remain valid
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
