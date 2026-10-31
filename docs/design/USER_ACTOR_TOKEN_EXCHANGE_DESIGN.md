# User + Actor Token Exchange: Design

Status: **Proposed** (2026-10-31). Nothing in this document is implemented yet except
where it says "today".
Owners: quantum-framework (`quantum-oauth-server`, `quantum-security-runtime`,
`quantum-models`), quantum-enterprise (`quantum-auth-service`), helixor-core (Python
receivers).

## 1. Problem

When one service calls another while serving a signed-in user, the receiving service
should see **who the user is** (realm, tenant, roles) and **which service is calling for
them**. Today it can see one or the other, not both:

| Today | What the receiver sees | Gap |
|---|---|---|
| Forward the user's access token | The user, resolved from signed claims | The calling service is invisible, so an operation that only a governed service may perform (activate an ontology TBox after a steward gate) is either open to the user directly or impossible. |
| Service token + `X-Acting-On-Behalf-Of-UserId` | The service (its userId, its roles) | The user is recorded but changes nothing: realm falls back to the system realm unless `X-Realm` is sent, tenant comes from `X-Tenant-Id`, roles are the service's. `AuditInterceptor` persists the acting user only when `impersonatedBy*` is also set. |
| Service token + `X-Realm` + `X-Tenant-Id` | The service, in a realm and tenant it named | The caller asserts the user's realm and tenant in unsigned headers. Every service re-implements that choice (Helixor DI's ontology client, helixor-core `delegated_principal`). |

Facts behind the table (1.4.2-SNAPSHOT):

- Service tokens carry `token_type=service`, `sub`, `groups`, `scope`, optional `azp`; no
  `realm`, `tenantId`, `userId` or `aud` (`TokenUtils` service claims;
  `CustomTokenAuthProvider` ignores the realm/audience arguments). Their realm/tenant
  boundary lives only server-side (`UserRealmRole.authorizedTenantIds/RegEx`,
  `CredentialUserIdPassword.realmRegEx`). Default lifetime 100 years; not revocable except
  by deleting the credential.
- `SecurityFilter.buildServiceClaimsContext` uses `X-Realm` or the system realm;
  `handleImpersonation` only records `X-Acting-On-Behalf-Of-*` on the context.
- Rule scripts and filter variables expose userId, defaultRealm, scope, roles,
  dataDomain, labels and resolver properties; not the acting user, the impersonator or
  `azp`. No rule can say "only when service X is calling".
- `/oauth/token` supports `authorization_code`, `client_credentials`, `refresh_token`.
  There is no `act`, `may_act` or token-exchange code anywhere.

## 2. Goals and non-goals

Goals

1. A user's identity propagates across service calls by token, never by headers the
   caller fills in.
2. One credential carries both parties: the user as subject, the calling service as actor.
3. Receivers resolve realm, tenant and roles exactly as for the user's own token, with no
   `X-Realm`/`X-Tenant-Id`.
4. Operations can require a specific actor (actor-gated), so a governed workflow cannot be
   bypassed by the user calling the target directly.
5. Every write records user and actor in audit.
6. Fail closed at every step.

Non-goals

- Background work with no live user. It keeps the service-token contract (§7).
- Cross-issuer federation. One issuer (quantum-auth-service) and one JWKS.
- Replacing impersonation (`X-Impersonate-*`), which stays an admin "run as" tool.

## 3. Decision

Implement **OAuth 2.0 Token Exchange (RFC 8693)** at `POST /oauth/token`, issuing
short-lived, audience-bound access tokens whose subject is the user and whose `act` claim
names the calling service.

Alternatives considered:

- **Two bearer tokens per call** (service token in `Authorization`, user token in a second
  header). No issuer change, but every receiver must verify two tokens, correlate them and
  merge identities; the user token is replayable at any audience; audit still has to be
  added. Rejected.
- **Receiver looks up the acting user** from `X-Acting-On-Behalf-Of-UserId`. Needs an
  identity lookup on every call (resource servers have no identity catalog) and trusts an
  unsigned claim from the caller. Rejected.
- **Forward the user token alone.** Correct and adopted for Helixor DI's ontology reads
  (helixor#760, in review), but cannot express actor-gated operations. Kept as the default for calls
  that need no actor (§6.1).

## 4. Protocol

### 4.1 Request

```
POST /oauth/token
Content-Type: application/x-www-form-urlencoded
Authorization: Bearer <actor service token>

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
subject_token=<user access token>
subject_token_type=urn:ietf:params:oauth:token-type:access_token
audience=<target application id, e.g. quantum-ontology-service>
scope=<optional, space separated; subset of what the target grants>
```

The actor authenticates with its own service token in `Authorization` (the platform's
existing service credential), not with `actor_token`. `actor_token` and
`requested_token_type` other than `access_token` are refused (`invalid_request`).

### 4.2 Checks (all must pass; any failure is a 400/401 with an OAuth error code and no token)

1. **Actor**: the bearer verifies against the issuer's key, `token_type=service`, and its
   credential still exists and is active (`CredentialType.SERVICE_TOKEN`). Deleting the
   credential stops all future exchanges even though old service tokens stay
   cryptographically valid.
2. **Subject**: `subject_token` verifies (signature, issuer, `exp`), is a user access token
   (`scope=authToken`, no `token_type=service`, no nested `act`; see §4.4), and its
   credential is active.
3. **Delegation grant**: the actor holds a `DelegationGrant` (§5) that allows
   - the requested `audience`,
   - the subject's realm (`realm` claim) and tenant (`tenantId` claim), within the actor's
     existing `realmRegEx` / `UserRealmRole.authorizedTenantIds|RegEx`,
   - every requested `scope`.
4. **Audience is real**: `audience` names a registered application in the subject's realm.
5. **Subject may use the audience**: the subject's own token lists the audience in `aud`,
   or the realm grants the audience to the subject (same rule the login path uses to fill
   `aud`). The exchange never widens what the user may reach.

### 4.3 Issued token

```json
{
  "iss": "<issuer>", "sub": "<user subject>", "userId": "<user id>",
  "realm": "<user realm>", "tenantId": "<user tenant>",
  "orgRefName": "...", "accountNum": "...", "realmRegEx": "<user's>",
  "groups": ["<user's roles>"],
  "aud": "<audience>", "azp": "<actor application id>",
  "scope": "authToken <granted scopes>",
  "act": { "sub": "<actor subject>", "userId": "svc-...", "azp": "<actor app>" },
  "token_type": "delegated",
  "iat": 0, "exp": "min(subject exp, iat + quantum.token-exchange.lifetime-seconds)"
}
```

- Lifetime default 300 s, configurable; never later than the subject token's `exp`.
- No refresh token. A service exchanges again per user request (or caches per
  user+audience until `exp`).
- `groups` are the user's roles for the subject's realm, exactly as the user's token.
  The actor's roles are **not** merged.
- Response: `{ "access_token", "issued_token_type": "urn:ietf:params:oauth:token-type:access_token", "token_type": "Bearer", "expires_in", "scope" }`.

### 4.4 Chains

A delegated token may not be used as `subject_token`. Service A calling B calling C
exchanges the **original user token** it received if it has one; if B holds only a
delegated token, B may forward it to C only when C is the same audience. Nested `act`
chains are out of scope for v1 and refused, so a token's actor is always the service
that asked for it.

## 5. Delegation grants

A `DelegationGrant` is configuration on the actor's service credential (new embedded list
on `CredentialUserIdPassword` for `SERVICE_TOKEN` credentials, admin-managed through the
service-token API and system-ux):

```json
{ "audiences": ["quantum-ontology-service"],
  "scopes": ["ontology.tbox.read", "ontology.tbox.admit"],
  "enabled": true }
```

Realm/tenant reach stays where it is today (`realmRegEx`, `UserRealmRole`), so a grant
cannot widen it. No grant → no exchange (`unauthorized_client`).

## 6. Receivers

### 6.1 Which call uses what

| Situation | Credential | Headers |
|---|---|---|
| User present, target needs only the user's permissions | The user's own access token, forwarded | none, except the user's own realm/tenant switch or impersonation passed on as made |
| User present, target operation is actor-gated | Exchanged token (`act` = caller) | none |
| No user (scheduled, queued, replayed work) | Caller's service token | `X-Tenant-Id` (+ `X-Realm` across realms); `X-Acting-On-Behalf-Of-UserId` for attribution |

### 6.2 Quantum `SecurityFilter`

- A token with `token_type=delegated` is verified like a user token (same JWKS, issuer,
  `exp`, `aud` must include this application) and builds the user's `PrincipalContext`
  through the existing user-token path: realm, tenant, roles from claims and credential.
- New `PrincipalContext` fields: `actorSubject`, `actorUserId`, `actorApplicationId`
  (from `act`). `X-Acting-On-Behalf-Of-*` and `X-Impersonate-*` on a delegated token are
  refused (400): the token already says who is acting.
- `X-Realm`/`X-Tenant-Id` on a delegated token follow the user-token rules (the user's
  own switch), validated against the user's credential, never the actor's.

### 6.3 Authorization: actor-gated operations

- Rule scripts and filter variables gain `actor.subject`, `actor.userId`,
  `actor.applicationId` (null when there is no actor). `RuleContext` identity matching is
  unchanged (the user's userId and roles).
- A policy marks an operation actor-gated with a condition, e.g. TBox activation:
  `actor.applicationId in ['helixor-di']`. The user's own roles still have to allow the
  action; the actor condition is an additional requirement, never a substitute.
- `/v1/authz/filter` evaluates the delegated principal the same way; the remote auth
  client forwards the delegated bearer unchanged.

### 6.4 Audit

`AuditInterceptor` records `actorSubject/actorUserId/actorApplicationId` whenever an actor
is present (and fixes today's gap: `actingOnBehalfOf*` is persisted for service-token
calls too, without requiring `impersonatedBy*`).

### 6.5 helixor-core (Python receivers)

- `delegated_principal()` accepts `token_type=delegated`: principal = the token's user
  (realm, tenant, roles from claims), `actor` = `act`. Headers are ignored for identity.
- The service-token + `X-Acting-On-Behalf-Of-UserId` path stays for background work but
  stops giving the user the service's roles (today it does): the principal is the service,
  with the user recorded for attribution.

## 7. Background work (no live user)

Unchanged contract, now documented as the only use of headers for identity: the service
authenticates as itself, names the tenant with `X-Tenant-Id` (and the realm with
`X-Realm` only when acting across realms), and records the user it works for with
`X-Acting-On-Behalf-Of-UserId`. The receiver authorizes **the service**. A job that must
act with a user's permissions later is a separate design (stored, revocable grant); it is
not solved by holding user tokens.

## 8. Security considerations

- **No privilege escalation**: the exchanged token holds exactly the user's roles; the
  actor's roles never merge in. Actor-gated operations need both.
- **Confused deputy**: `aud` binds a token to one target; receivers reject other
  audiences. A service cannot obtain a token for a user who has not sent it a valid token.
- **Replay**: 300 s lifetime, no refresh, audience-bound.
- **Revocation**: actor revocation = delete/disable the service credential or its grant
  (checked on every exchange). Subject revocation = existing credential disable (checked
  on every exchange; an issued 300 s token is not recalled).
- **Long-lived service tokens** (100 y) remain a separate risk; the exchange limits their
  blast radius to granted audiences and the user's own permissions.
- **Logging**: never log subject, actor or issued tokens; log subject userId, actor
  userId, audience, decision.

## 9. Rollout

| Phase | Change | Repos |
|---|---|---|
| 0 | Forward the user's own token for user-present reads (Helixor DI ontology reads: helixor#760, in review). Agent and user docs state the pattern. | helixor, helixor-ai-skills, quantum-framework docs |
| 1 | `PrincipalContext` actor fields, rule/filter variables, audit recording (incl. the acting-on-behalf gap). Delegated-token verification in `SecurityFilter`. | quantum-framework |
| 2 | Token-exchange grant + `DelegationGrant` model + admin API; discovery advertises the grant. | quantum-framework (`quantum-oauth-server`), quantum-enterprise (`quantum-auth-service`) |
| 3 | Ontology service marks TBox submit/activate actor-gated (`helixor-di`); Helixor DI admissions exchange the steward's token instead of service delegation. | quantum-enterprise, helixor |
| 4 | helixor-core `delegated_principal` accepts delegated tokens; solver and other Helixor receivers move; header delegation narrowed to background work. | helixor-core, helixor |

Each phase ships behind no flag: receivers accept delegated tokens before any caller
sends them (phase 1 before 2), and callers switch per operation (phase 3+).

## 10. Test plan

Issuer (phase 2), `OAuthTokenResource` / exchange service:

| # | Case | Expect |
|---|---|---|
| E1 | Valid actor + subject + grant | 200; claims per §4.3; `exp` ≤ subject `exp`; no actor roles in `groups` |
| E2 | Actor bearer is a user token | 401 `invalid_client` |
| E3 | Actor credential deleted / disabled | 401 `invalid_client` |
| E4 | No grant for audience | 400 `unauthorized_client` |
| E5 | Subject realm/tenant outside actor's reach | 400 `invalid_target` |
| E6 | Subject token expired / wrong issuer / bad signature | 400 `invalid_grant` |
| E7 | Subject token is a service or delegated token | 400 `invalid_grant` |
| E8 | Audience not registered in subject's realm, or not granted to subject | 400 `invalid_target` |
| E9 | Scope not in grant | 400 `invalid_scope` |
| E10 | `actor_token` or other `requested_token_type` sent | 400 `invalid_request` |
| E11 | Subject credential disabled after its token was issued | 400 `invalid_grant` |
| E12 | Tokens never appear in logs (log capture) | pass |

Receiver (phase 1), `SecurityFilter` / `RuleContext` / `AuditInterceptor`:

| # | Case | Expect |
|---|---|---|
| R1 | Delegated token, no headers | user's realm, tenant, roles; actor fields set |
| R2 | Delegated token for another audience | 401 |
| R3 | Delegated token + `X-Acting-On-Behalf-Of-*` or `X-Impersonate-*` | 400 |
| R4 | Delegated token + user's own `X-Realm` within user's `realmRegEx` | switched; outside → 403 |
| R5 | Actor-gated rule, right actor, user allowed | allow |
| R6 | Actor-gated rule, user token without actor | deny |
| R7 | Actor-gated rule, right actor, user not allowed | deny |
| R8 | Write under delegated token | audit has user and actor |
| R9 | Write under service token + acting header | audit has the acting user (gap fix) |
| R10 | `/v1/authz/filter` with delegated bearer | same decision as local evaluation |

End to end (phase 3), dev or an isolated stack:

| # | Case | Expect |
|---|---|---|
| X1 | Steward admits a proposal in DI | DI exchanges the steward's token; TBox activates; audit shows steward via helixor-di |
| X2 | Same user calls ontology activate directly with own token | 403 (actor-gated) |
| X3 | DI read paths | user token forwarded, no realm/tenant headers (helixor#760 tests) |
| X4 | Background reindex job | service token + `X-Tenant-Id`; authorized as the service |

helixor-core (phase 4): delegated token → user principal with `actor`; service token +
acting header → service principal (no role transfer); missing/foreign `aud` → 401.

## 11. Open questions

1. Grant scopes: per audience free-form strings, or derived from the target application's
   declared operations (system-registrar app declarations)? Proposed: declared, so the
   grant UI can list them.
2. Should the ontology service also accept the steward's plain token for admission when
   the realm opts out of actor gating? Proposed: no; one path.
3. Exchange caching in callers: per (user subject, audience) until `exp - 30 s`.
