# Identity-Only Tokens: Auth Resolves The Principal

Status: **Proposed** (2026-11-12). Needs owner sign-off before implementation.
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
  "actingOnBehalfOfUserId": "..."          // optional: service calls, attribution
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
  "actingOnBehalfOfUserId": ... | null,
  "cacheSeconds": 60
}
```

Typed refusals: `PRINCIPAL_UNKNOWN` (401), `PRINCIPAL_DISABLED` (401),
`APPLICATION_NOT_GRANTED` (403), `REALM_NOT_AUTHORIZED` (403),
`TENANT_NOT_AUTHORIZED` (403), `IMPERSONATION_REFUSED` (403).

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

### 4. Service tokens v2

- A service is a credential like any other. Its token is identity only
  (section 1) and carries no roles, realm or tenants.
- Its roles per application come from grants, exactly as a person's do: the
  staff app's Access page grants the service identity (or a group it belongs
  to) roles in a realm for an application. Changing them needs no new token.
- The realms and tenants it may act in are the credential's `authorizedRealms`
  / realm assignments. A call names them with `X-Realm` / `X-Tenant-Id`;
  resolution checks them.
- Issuing: `POST /security/service-token { description, realm }` returns the
  token and its `credentialId` (`jti`). No roles, no expiry, no audiences, no
  identity-bound "own" kind. Revoking the credential stops it on the next
  resolution (within the cache window).
- Who may issue: an `admin` of the realm the service is created in (decision
  below).

The staff app's Service accounts page reduces to: name, realm, Issue; grants
are set on the Access page.

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

## Decisions for the owner

1. Cache window of 60 seconds for grant changes and revocation to take effect.
2. Issuing service tokens: realm `admin` (proposed) instead of the
   `mint-own-service-token` / `mint-agnostic-service-token` roles, which are
   retired.
3. Drop identity-bound ("own") service tokens entirely.
4. Applications move to framework 1.4.3 for step 2 (an exception to staying on
   1.4.2).

## Superseded

- `USER_ACTOR_TOKEN_EXCHANGE_DESIGN.md` (already rejected).
- Application audience admission and `azp`-based role selection.
- Embedding roles and tenancy in tokens.
