# Quantum Central Auth and Control Plane Service

## Status

Implementation design with initial wrapper-service slice in progress.

The deployable `quantum-auth-service` belongs in `quantum-enterprise`, not the
open-source framework reactor, because HelixCode can deploy enterprise modules
and the service needs the enterprise `quantum-oidc-provider` to support generic
OIDC/GitHub login. The open-source `quantum-framework` remains the reusable
contract/resource/provider foundation.

This document intentionally steps back from the current `quantum-auth-service`
prototype. That prototype should be treated as disposable unless a class is
proven to be deployment glue around existing Quantum capabilities. The service
we need is not a new authentication or authorization framework. It is a
deployable boundary around the authentication, authorization, policy,
functional-domain, tenant, organization, account, feature-flag, impersonation,
realm, and query/filter capabilities that already exist in Quantum.

## Goal

Create a separately deployable Quantum auth/control-plane service that Helixor,
Quantum applications, and other clients can use for:

- authentication and provider discovery;
- authorization checks and policy diagnostics;
- principal and resource context evaluation;
- realm override and impersonation behavior;
- functional area, functional domain, and functional action catalog access;
- tenant, organization, account, realm, and membership access;
- feature flag and entitlement access;
- policy/query filter generation for provider-backed data access.

The service must reuse existing Quantum modules and contracts. New code is
allowed only when it is a thin facade, deployment adapter, SDK contract, or
provider-specific binding that does not already exist.

## Non-Goals

- Do not create a parallel authz model hierarchy.
- Do not create new `PrincipalContext`, `ResourceContext`, policy, tenant,
  organization, account, feature flag, functional-domain, or credential models.
- Do not hard-code org, tenant, GitHub owner, role, realm, or data-domain
  filters in the service.
- Do not implement a new policy engine.
- Do not implement a new auth provider chain.
- Do not duplicate existing policy-check, debug, authorization, authentication,
  impersonation, or X-Realm behavior.
- Do not build Python-side or Helixor-side control-plane tables that compete
  with Quantum as the source of truth.

## Reuse Gate

Before adding any class, endpoint, DTO, or repository to this service, answer:

1. Does an existing Quantum module already expose this behavior?
2. Does an existing model already represent this concept?
3. Does an existing resource already expose this endpoint shape?
4. Does an existing repository already persist this entity?
5. Does an existing provider binding already lower the query/filter?
6. If we still add code, is it only facade/config/deployment/SDK glue?

If the answer to 1-5 is yes, reuse it. If the answer to 6 is no, do not add the
code.

## Existing Quantum Capabilities To Reuse

### Authentication

Source of truth:

- `quantum-models`
  - `com.e2eq.framework.model.auth.AuthProvider`
  - `com.e2eq.framework.model.auth.AuthProviderFactory`
  - `com.e2eq.framework.model.auth.UserManagement`
  - `com.e2eq.framework.model.auth.ClaimsAuthProvider`
- `quantum-jwt-provider`
  - `com.e2eq.framework.model.auth.provider.jwtToken.CustomTokenAuthProvider`
- `quantum-enterprise`
  - `quantum-oidc-provider`, discovered through `AuthProviderFactory` when the
    enterprise service is deployed
- Cognito provider module when needed
- `quantum-framework`
  - `com.e2eq.framework.rest.resources.AuthResource`
  - `com.e2eq.framework.rest.resources.SecurityResource`
- `quantum-oauth-server`
  - OAuth/OIDC authorize/token/userinfo/JWKS/discovery resources

Important existing behavior:

- `auth.provider=custom,oidc` default chain.
- provider discovery must read `AuthProviderFactory` rather than parsing config
  independently;
- explicit provider override;
- credential-level provider override;
- fallback through configured providers;
- custom JWT credential persistence;
- OIDC as the generic path for GitHub and later Cognito-compatible login;
- existing OAuth/OIDC server endpoints.

Inventory note: `quantum-oauth-server` exposes OAuth/OIDC server endpoints and
`quantum-jwt-provider` exposes the custom JWT provider in the open-source
framework reactor. The external OIDC client provider is enterprise-owned:
`quantum-enterprise/quantum-oidc-provider`. The service package used by
HelixCode should therefore be an enterprise deployable wrapper that depends on
that provider while reusing the framework resources unchanged.

### Authorization And Policy

Source of truth:

- `quantum-models`
  - `PrincipalContext`
  - `ResourceContext`
  - `SecurityCallScope`
  - `SecurityCheckResponse`
  - `Rule`, `Policy`, `DataDomainPolicy`
  - functional area/domain/action models if present
- `quantum-morphia-repos`
  - `RuleContext`
  - `RepoSecurityFilterBuilder`
  - `RuleScriptExecutor`
  - `RuleFilterApplicabilityEvaluator`
  - `PolicyRepo`
- `quantum-framework`
  - `SecurityFilter`
  - policy resources and debug/check endpoints

Important existing behavior:

- policy check/evaluation endpoints already exist;
- policy debug capabilities already exist;
- `RuleContext.checkRules(...)` is the authorization engine;
- field exclusions are already resolved through `RuleContext.getExcludedFieldPaths(...)`;
- pre-authorization is already enforced by `SecurityFilter`;
- policy scripts and rule matching already exist;
- request cache and rule reload behavior already exist.

### Realm Override And Impersonation

Source of truth:

- `SecurityFilter`
- `CredentialUserIdPassword`
- `BaseAuthProvider`
- `PrincipalContext`
- `SecurityCallScope`

Important existing behavior:

- `X-Realm` override already adjusts the effective `PrincipalContext`.
- impersonation already validates impersonation scripts.
- impersonation tracks original and effective subject/user.
- impersonation takes precedence over `X-Realm`.

### Query And Filter Binding

Source of truth:

- `quantum-models`
  - ANTLR grammar `BIAPIQuery.g4`
- `quantum-framework`
  - `QueryPredicates`
  - in-memory JSON predicate evaluation
- `quantum-morphia-repos`
  - `QueryToFilterListener`
  - `ValidatingQueryToFilterListener`
  - `MorphiaRepo`
  - `QueryGateway`

Important existing behavior:

- BIAPI query language is already the filter language.
- Mongo/Morphia lowering already exists.
- in-memory lowering already exists.
- the provider binding pattern can be extended to DynamoDB later without
  changing the authorization model.

### Control Plane

Source of truth should remain existing Quantum models, repos, and resources for:

- tenants;
- organizations;
- accounts;
- realms;
- memberships;
- user profiles;
- credentials;
- functional areas;
- functional domains;
- functional actions;
- feature flags;
- entitlements;
- policy catalogs.

The implementation phase must inventory exact model/resource/repo names before
writing code. If a concept already exists, use the existing concept.

## Credential Realm Strategy

Credential lookup is a special case and must be designed explicitly. Login
starts with only a `userId`, password or external-provider assertion, and
optional provider hints. At that point the service has not yet resolved a
tenant, realm, or `DomainContext`. That means authentication needs a global
identity registry step before the normal realm-scoped authorization model can
run.

The existing implementation already leans in this direction:

- `CredentialRepo` defaults no-realm credential reads and writes to
  `envConfigUtils.getSystemRealm()`;
- `AuthResource` first looks up login credentials in the system realm;
- `SecurityFilter` resolves JWT credentials and impersonation targets from the
  system realm;
- realm catalog lookups also use the system realm, while a resolved
  credential's `DomainContext` supplies the effective default realm and data
  domain.

That may be the correct shape for a separately deployed, tenant-agnostic auth
service, but it must not remain an accidental hard-coded assumption scattered
through resources and providers.

### Recommended Model

Use a central credential registry for authentication, then resolve into normal
realm/domain context for authorization and data access.

Flow:

1. Resolve the credential from a global registry using `userId`, subject,
   provider assertion, or provider-specific identity.
2. Authenticate through the existing `AuthProviderFactory` chain and
   credential-level provider override.
3. Read the credential's `DomainContext`, memberships, allowed realms,
   roles/groups, and provider metadata.
4. Build `PrincipalContext` from the credential's resolved default realm and
   data domain.
5. If an `X-Realm` header is present, apply it only after the base principal is
   resolved and only if the credential/policy allows that target realm.
6. Run normal `RuleContext` authorization and provider filter generation using
   the effective realm/domain context.

In this model, `X-Realm` does not mean "look up the login credential in that
realm." It is an optional realm override: when absent, the credential's resolved
default realm and `DomainContext` are used; when present and authorized, the
request operates in that allowed target realm. The credential registry
identifies the person or service principal; the resolved principal context
determines where that identity can act.

### Design Requirement

Introduce or reuse one small credential lookup boundary so resource/provider
code does not call `envConfigUtils.getSystemRealm()` directly for credential
identity decisions.

The boundary should make these choices explicit:

- credential registry realm, defaulting to the configured system/auth realm;
- whether credential lookup ignores data-domain filters during authentication;
- whether multi-realm credential lookup is enabled at all;
- lookup order for user id, subject, external provider subject, and email;
- whether provider hints or credential-level provider metadata override the
  default chain;
- how duplicate user ids across realms are rejected, namespaced, or resolved.

The current no-realm `CredentialRepo` methods can remain as repository
convenience methods only if they are documented as central-registry methods.
Otherwise, auth resources should call an explicit service such as
`CredentialRegistry` or `CredentialLookupService`, which may delegate to
`CredentialRepo`.

Phase 0 decision: use the existing system/auth realm as the central credential
registry for the first deployable service. Make that choice explicit through a
small lookup boundary before adding new behavior. Do not make `X-Realm`
required for login, and do not use `X-Realm` as the primary credential lookup
realm. `X-Realm` remains an optional post-authentication override validated
against accessible realms and policy.

Known inconsistencies to address in Phase 1:

- `CredentialRepo` intentionally routes default credential CRUD/list/update
  operations to `envConfigUtils.getSystemRealm()`, while auth resources and
  filters also make direct system-realm calls. This is the right model, but the
  call sites should be centralized.
- `SecurityResource` exposes a `PUT /security/disableImpersonation` method that
  calls `enableImpersonationWithSubject(...)`; the path/name mismatch should be
  fixed or compatibility-shimmed.
- `BaseAuthProvider.enableImpersonationWithUserId/Subject(...)` looks up the
  credential in the supplied `realmToEnableIn`, while most central identity
  lookup uses the system realm. That should be reviewed before relying on
  cross-realm impersonation from the central service.

### Alternatives

Central registry:

- credentials live in one auth/system realm;
- credential `DomainContext` and memberships point to effective realms/orgs;
- simplest login and cross-org identity model;
- best fit for GitHub/Gmail identities where one user may belong to multiple
  organizations;
- requires strict uniqueness and audit rules for user id, email, and external
  provider subject.

Multi-realm credential storage:

- credentials live inside each tenant/realm;
- closer to generic realm-scoped repository behavior;
- requires login realm discovery before authentication;
- makes a single human identity across multiple orgs harder to reason about;
- increases risk of duplicate accounts and fragmented usage/audit trails.

The initial central-service design should prefer the central registry unless
Phase 0 proves existing Quantum deployments depend on multi-realm credential
storage.

## Deployable Service Shape

The deployable module may be named `quantum-auth-service` initially, but its
actual responsibility is central auth and control-plane exposure. If the name
causes confusion, rename it before the API hardens.

The module should contain:

- `pom.xml`;
- `Dockerfile`;
- `application.properties`;
- local Docker compose files;
- health/config/OpenAPI exposure;
- thin facade resources only when existing resources do not already provide the
  needed public contract;
- local bootstrap code only when it uses existing `UserManagement` and repos.

The module should depend on existing modules:

- `quantum-models`;
- `quantum-framework`;
- `quantum-morphia-repos`;
- `quantum-jwt-provider`;
- an external OIDC client provider module when found or added;
- `quantum-oauth-server`;
- Cognito provider later when needed.

The module should not contain:

- new authz DTO trees;
- new policy engine classes;
- new principal/resource models;
- new org/tenant/account/feature/domain models;
- hard-coded policy decisions;
- direct credential fabrication that bypasses `UserManagement`.

## API Strategy

### Reuse Existing Endpoints First

Existing endpoints for authentication, authorization checks, policy debug, and
security resources remain canonical. Implementation must first produce an API
inventory table before adding anything.

Phase 0 inventory:

| Capability | Existing endpoint/resource | Existing model/response | Reuse as-is? | Gap |
| --- | --- | --- | --- | --- |
| Auth provider name | `GET /auth/provider/name` in `AuthResource` | provider name string | Yes | None |
| Create user | `POST /auth/create-user` in `AuthResource` | existing `UserManagement` result | Yes | Must use `AuthProviderFactory`, not local fabrication |
| Login | `POST /auth/login` and richer `POST /security/login` | existing auth response / `AuthResponse` | Yes | Standardize which login surface Helixor uses |
| Refresh | `POST /auth/refresh` and `POST /security/refresh` | existing auth response / `AuthResponse` | Yes | Standardize which refresh surface Helixor uses |
| Service token | `POST /auth/service-token` | existing service-token response | Yes | Useful for companion/MCP agents |
| Registration | `POST /security/register`, `GET /security/register` | `RegistrationRequest`, `ApplicationRegistration` | Yes | Keep anonymous save scoped through existing ignore-rules behavior |
| Current user | `GET /security/me` | identity/profile/realm summary | Yes | May need SDK-safe projection for portal |
| Logout/health/auth test | `POST /security/logout`, `GET /security/healthCheck`, `GET /security/authenticated/test` | existing responses | Yes | None |
| Realm override admin | `PUT /security/enableRealmOverride`, `PUT /security/disableRealmOverride` | existing user-management calls | Yes | Audit auth-provider implementation consistency |
| Impersonation admin | `PUT /security/disableImpersonation`, `PUT /security/disableImpersonation/withSubject/{subject}` | existing user-management calls | Partly | Path name appears wrong: one `disableImpersonation` method enables impersonation |
| Policy CRUD/import | `/security/permission/policies` via `PolicyResource` and `BaseResource`; `POST /refreshRuleContext`, `POST /import` | `Policy`, `PolicyRepo` | Yes | None |
| Policy check | `POST /system/permissions/check` | `PermissionResource.CheckResponse` with `SecurityCheckResponse` | Yes | Canonical check endpoint for Helixor |
| Indexed policy check | `POST /system/permissions/check-with-index` | `RuleIndexSnapshot` | Yes | Useful for diagnostics and bulk UX |
| Policy evaluation/debug | `POST /system/permissions/fd/evaluate`, `POST /system/permissions/role-provenance`, `GET /system/permissions/entities`, `GET /system/permissions/fd` | existing debug/evaluation projections | Yes | Keep response typed in SDK |
| Functional domains/actions | `/security/functionalDomain` via `FunctionalDomainResource` and `/system/permissions/fd` | `FunctionalDomain`, derived area/domain/action catalog | Yes | Prefer `/system/permissions/fd` for read catalog; resource for CRUD |
| Feature flags | `/features/flags` via `FeatureFlagResource` | `FeatureFlag` | Yes | Entitlement-specific resource still needs inventory |
| Realms | `/security/realm`, plus `GET /byTenantId`, `GET /possibleImpersonations/realms`, `GET /accessible` | `Realm` | Yes | None |
| Realm memberships | `/security/realm-memberships` via `RealmTenantMembershipResource` | `RealmTenantMembership` | Yes | None |
| Organizations | `/security/accounts/organizations` via `OrganizationResource` | `Organization` | Yes | None |
| Accounts | `Account`, `AccountRepo`, registration/provisioning flows | `Account` | Partly | No direct `AccountResource` found; decide whether existing flows are enough |
| Tenant provisioning | `/admin/tenants`, `/admin/tenants/runs/{executionRef}`, `/admin/tenants/runs/{executionRef}/retry`, `DELETE /admin/tenants/{realmId}` | tenant provisioning request/run responses | Yes | Admin-only, not generic tenant CRUD |
| Tenant workflow/onboarding | `/admin/tenants/workflow/current`, `/onboarding/workflow/current`, `/onboarding/run/start`, `/onboarding/run/{runRef}` | workflow definitions/runs | Yes | Keep as workflow layer |
| User profile | `/user/userProfile`, `GET /byRefName`, `PUT /updateStatus`, `POST /create` | `UserProfile` | Yes | None |
| Credentials | `/user/credentials`, `POST /changePassword`, `POST /changeEmail`, `POST /resendTemporaryPassword`, `GET /matching-realms`, `GET /byUserId`, `POST /resetPassword` | `CredentialUserIdPassword` | Yes | Should route identity lookup through explicit registry boundary |
| Query/filter gateway | `/api/query/plan`, `/find`, `/count`, `/save`, `/delete`, `/deleteMany`, import/export endpoints | existing query gateway contracts | Yes for data operations | Need a stable authorization-filter projection if provider filters are a product API |
| OAuth/OIDC server | `/.well-known/openid-configuration`, `/oauth/authorize`, `/oauth/token`, `/oauth/userinfo`, `/oauth/jwks` | OAuth/OIDC responses | Yes | This is server functionality, not proof of an external GitHub OIDC provider |
| Provider discovery facade | `GET /auth/providers`, `GET /auth/oidc/session` in `quantum-auth-service` | provider/session projection | Thin facade allowed | Must continue delegating to `AuthProviderFactory` |

### New Facades Are Only Allowed For Gaps

Potential facades:

- provider discovery facade, because existing `/auth/provider/name` exposes
  only one provider name and the UI needs discovered/configured provider state;
- filter-generation facade, only if existing policy-check/query endpoints do
  not expose a provider-neutral or provider-specific filter result;
- SDK projection facade, if existing responses are too internal for generated
  clients.

Any facade must delegate to existing Quantum services.

Implemented facade decision:

- `GET /v1/authz/filter` is an authenticated thin facade in the enterprise
  `quantum-auth-service` wrapper.
- It delegates to the existing request `PrincipalContext`, optional `X-Realm`
  behavior, and `RuleContext.checkRules`.
- It accepts functional area/domain/action plus optional data-domain fields
  (`orgRefName`, `accountNumber`, `tenantId`, `dataSegment`, `ownerId`,
  `locationId`, `businessTransactionId`) so callers can ask for the authorized
  filter in an explicit scope without inventing a parallel policy model.
- It returns the full effective `resourceContext` with nested `dataDomain`, the
  aggregate `quantumFilter`, split `dataDomainFilter` and `businessFilter`
  projections, field exclusions, and optional raw check diagnostics.
- It is not a new authorizer. Missing or contradictory behavior must be fixed
  in `RuleContext`, data-domain resolution, BIAPI query lowering, or provider
  bindings.

## Filter Generation Design

The only likely missing capability is a stable provider-facing filter contract.
`POST /system/permissions/check` and `POST /system/permissions/fd/evaluate`
already evaluate policy decisions. `QueryGatewayResource` and the BIAPI
ANTLR/Morphia/in-memory query bindings already lower query expressions to data
providers. The gap is not a new policy engine; it is a stable projection that
returns the authorized filter/exclusions/diagnostics in a form SDK clients can
consume.

Input:

- bearer token;
- optional `X-Realm`;
- optional existing impersonation headers;
- functional area;
- functional domain;
- functional action;
- optional resource id;
- optional owner id;
- target provider: `mongo`, `memory`, later `dynamo`;
- optional model/entity type when provider lowering needs it.

Flow:

1. Reuse existing request/security machinery to resolve `PrincipalContext`.
2. Build or reuse `ResourceContext`.
3. Call `RuleContext.checkRules(...)`.
4. Collect rule decision, matched rules, filters, excluded fields, reasons, and
   diagnostics from existing response/context APIs.
5. Lower filters through provider binding:
   - Mongo: existing `QueryToFilterListener`;
   - memory: existing `QueryPredicates`;
   - DynamoDB: new future provider adapter only.
6. Return an SDK-safe projection.

Provider adapter interface, if needed:

```java
interface QuantumPolicyFilterBinding<T> {
    String provider();
    T lower(String biapiFilter, PrincipalContext principal, ResourceContext resource, Class<?> modelClass);
}
```

Do not place policy decisions in provider adapters. Adapters only lower an
already-authorized filter expression.

## Helixor Code Integration

Helixor Code should use Quantum as source of truth for identity and control
plane. Helixor Code should only store Helixor-specific artifacts.

Quantum-owned concepts:

- user identity;
- subject;
- roles;
- organizations;
- tenants;
- accounts;
- realms;
- memberships;
- functional domains/actions;
- feature flags/entitlements;
- authorization policy;
- impersonation;
- realm override.

Helixor-owned artifacts:

- code indexes;
- workspace channels;
- designs;
- TODO stacks;
- companion registrations;
- usage/token-savings events;
- product feedback;
- index/license receipts only if not modeled centrally.

Every Helixor artifact should be stamped with Quantum identifiers where
available:

- `subject_id`;
- `user_id`;
- `realm`;
- `tenant_id`;
- `org_ref_name`;
- `account_id`;
- `functional_area`;
- `functional_domain`;
- `functional_action`;
- optional `github_owner`;
- optional `github_repo`.

Before Helixor reads or writes an artifact, it should call the existing Quantum
authorization check/debug endpoint or the minimal filter facade if the operation
needs a data-provider filter.

Examples:

- `helixor-code / indexes / read`;
- `helixor-code / indexes / write`;
- `helixor-code / workspace / read`;
- `helixor-code / todos / write`;
- `helixor-code / usage / read`;
- `helixor-code / companions / manage`.

## Migration From Current Prototype

Treat these as wrong-path until proven otherwise:

- `quantum-auth-service/src/main/java/com/e2eq/framework/authz/model/*`;
- hard-coded `CentralAuthzService`;
- tests that assert hard-coded org/tenant/GitHub constraints.

Keep or salvage only:

- `pom.xml` if the dependencies are correct;
- `Dockerfile`;
- `application.properties`;
- local compose files;
- local bootstrap if it continues to use `AuthProviderFactory`,
  `UserManagement`, and existing repos.

## Implementation Phases

### Phase 0: Inventory

- List existing auth endpoints and exact paths.
- List existing policy check/debug endpoints and exact paths.
- List existing functional-domain/action/catalog endpoints.
- List existing tenant/org/account/realm/membership endpoints.
- List existing feature flag/entitlement endpoints.
- Audit `CredentialRepo`, `AuthResource`, `SecurityResource`,
  `SecurityFilter`, `BaseAuthProvider`, and concrete auth providers for direct
  system-realm credential assumptions.
- Decide whether credential lookup is a central registry, multi-realm lookup,
  or configurable strategy, and document the exact lookup order.
- Identify whether filter generation already exists.
- Document exact request/response contracts.

No new endpoint should be written in this phase.

### Phase 1: Strip Service To Wrapper

- Remove wrong-path authz model/service/resource code.
- Keep deployable module structure.
- Ensure existing resources from dependencies are indexed and exposed by Quarkus.
- Ensure Mongo config points to the configured local/cloud Mongo instance.
- Ensure custom JWT and OIDC provider chain is configured through existing
  `AuthProviderFactory`.
- Ensure runtime JWT key locations are environment/file references, not PEM
  files packaged in the deployable jar.

### Phase 2: Facade Only For Proven Gaps

- Add provider discovery facade only if needed.
- Add filter-generation facade only if no existing endpoint satisfies it.
- Place reusable facade code in `quantum-framework` or a small runtime module,
  not buried in the deployable service, unless it is purely deployment-specific.
- Current slice adds the filter-generation facade inside the enterprise wrapper
  as a deployment-specific projection over `RuleContext`; promote it to a shared
  runtime module only if another product needs the same route.

### Phase 3: SDK Contract

- Generate OpenAPI for the reused/facade endpoints.
- Feed OpenAPI into `helixor-sdk-gen` or the appropriate SDK generator.
- Ensure Java clients consume SDK bindings instead of hard-coded URLs.
- Generate Python/JavaScript clients for Helixor Code.

### Phase 4: Helixor Integration

- Replace Helixor-local identity/org assumptions with calls to Quantum.
- Keep Helixor artifact storage but stamp records with Quantum identifiers.
- Use Quantum authz/filter calls before artifact reads/writes.
- Remove or downgrade Helixor admin screens that imply Helixor owns identity or
  org control-plane data.

### Phase 5: End-To-End Tests

Required tests:

- custom JWT login reads/writes Mongo-backed credential data;
- provider discovery reports the configured chain and the providers discovered
  by `AuthProviderFactory`;
- OIDC provider is advertised from the factory inventory, while UI/session
  readiness still depends on OIDC client configuration;
- login resolves a global credential registry entry into the expected default
  `DomainContext`;
- authentication can ignore data-domain filters only at the credential registry
  step, not during normal authorization;
- credential-level provider override is honored;
- fallback provider chain uses `custom,oidc`;
- existing policy check endpoint returns the expected allow/deny;
- existing policy debug endpoint remains reachable;
- absent `X-Realm` uses the credential's default `DomainContext`;
- present and authorized `X-Realm` changes effective context;
- impersonation uses existing script validation;
- filter generation, if added, delegates to existing BIAPI provider binding;
- Helixor Code artifact read/write is denied when Quantum policy denies;
- Helixor Code artifact read/write is allowed and scoped when Quantum policy
  allows.

## Initial Implementation Notes

The first implementation pass intentionally keeps `quantum-auth-service` thin:

- wrong-path duplicate authz DTO/service/resource code was removed;
- Quarkus indexing/dependencies expose existing Quantum resources such as
  `/auth/login`, `/security/register`, `/security/permission/policies`,
  `/system/permissions/check`, `/system/permissions/fd/evaluate`,
  `/system/permissions/role-provenance`, `/admin/tenants`, OAuth discovery, and
  JWKS;
- `AuthProviderDiscoveryResource` delegates provider inventory to
  `AuthProviderFactory` and reports both the configured chain and the discovered
  provider implementations;
- `/security/register` remains `@PermitAll`, but its registration-request save
  uses the existing `SecurityCallScope.openIgnoringRules()` scope so anonymous
  registration is not blocked by the persistence rule interceptor;
- runtime JWT signing and verification keys are external file/env references
  through `QUANTUM_JWT_PRIVATE_KEY_LOCATION` and
  `QUANTUM_JWT_PUBLIC_KEY_LOCATION`; PEM files are allowed only in
  `src/test/resources` or external dev/test secret mounts, not in
  `src/main/resources`.

Focused wrapper tests now cover:

- provider discovery through the factory;
- custom JWT login using the local bootstrap credential;
- public registration create/read;
- admin user creation, login, lookup, and deletion via existing credential APIs;
- policy create, rule-context refresh, permission check, permission evaluate,
  role-provenance debug, and policy delete;
- tenant provision/delete through the existing control-plane resource;
- OAuth discovery, JWKS, health check, and OpenAPI exposure of reused resources.

Verification performed:

- `mvn -pl quantum-models,quantum-framework -am install -DskipTests`
- `mvn -pl quantum-auth-service clean test`
- `QUANTUM_JWT_PRIVATE_KEY_LOCATION=file:/tmp/quantum-auth-keys/privateKey.pem QUANTUM_JWT_PUBLIC_KEY_LOCATION=file:/tmp/quantum-auth-keys/publicKey.pem MP_JWT_VERIFY_PUBLICKEY_LOCATION=file:/tmp/quantum-auth-keys/publicKey.pem SMALLRYE_JWT_SIGN_KEY_LOCATION=file:/tmp/quantum-auth-keys/privateKey.pem mvn -pl quantum-auth-service package -DskipTests`
- runtime jar scan confirmed no `*.pem` resources in the Quarkus application jar
  or dependency jars.

## SDK/OpenAPI Slice

The service now has a generated contract owned beside the deployable enterprise
service:

- `quantum-auth-service/contracts/quantum-auth-service.openapi.yaml` is a
  runtime snapshot from `/q/openapi`;
- `quantum-auth-service/contracts/quantum-auth-service-sdk.yml` is the
  `helixor-sdk-gen` config for Java, Python, and TypeScript clients;
- `quantum-auth-service/generated/quantum-auth-service-sdk/` contains the
  generated SDK artifacts, including `operation-contracts.json`.

Wrapper resources were tightened only where the generated contract needed typed
facade responses:

- `GET /auth/providers` returns `AuthProvidersResponse` from
  `AuthProviderFactory` discovery data;
- `GET /v1/authz/filter` returns `AuthzFilterResponse` with typed data-domain,
  resource-context, principal-context, filter-rule, and optional raw
  `SecurityCheckResponse` fields.

Generating against the full reused Quantum service surface also found and fixed
two `helixor-sdk-gen` gaps:

- OpenAPI parameter lists are now de-duplicated by `(in, name)` so path-level
  and operation-level duplicates do not produce duplicate SDK method
  parameters.
- Java type generation now resolves `$ref` aliases to scalar and array schemas
  before emitting types, so references such as `Date`, `Instant`, and
  `UIActionList` compile as `LocalDate`, `OffsetDateTime`, or `List<T>` instead
  of missing model classes.

Verification performed:

- `PYTHONPATH=src /Users/mingardia/dev/helixor/.venv/bin/python -m pytest tests/test_generator.py tests/test_central_authz_contract.py -q`
- `mvn -pl quantum-auth-service -Dtest=QuantumAuthServiceWrapperTest test -q`
- `PYTHONPATH=/Users/mingardia/dev/helixor/helixor-sdk-gen/src /Users/mingardia/dev/helixor/.venv/bin/python -m helixor_sdk_gen.cli generate quantum-auth-service/contracts/quantum-auth-service-sdk.yml quantum-auth-service/contracts/quantum-auth-service.openapi.yaml --out quantum-auth-service/generated/quantum-auth-service-sdk --local`
- `/Users/mingardia/dev/helixor/.venv/bin/python -m compileall -q quantum-auth-service/generated/quantum-auth-service-sdk/python/quantum_auth_service`
- `mvn -q -DskipTests compile` in
  `quantum-auth-service/generated/quantum-auth-service-sdk/java`

## Helixor Code Consumer Integration Slice

Helixor Code now has a first consumer integration against the generated Quantum
auth/control-plane contract while preserving the existing Dynamo/seeded/cloud
auth fallback paths.

Backend bridge changes:

- `helixor_code_backend.central_authz` loads the generated
  `quantum_auth_service` Python SDK when available, with the REST fallback still
  in place for beta diagnostics.
- The bridge maps Helixor Code workspace context into Quantum filter parameters:
  selected org becomes `orgRefName` and `tenantId`, the active subject becomes
  `ownerId`, and workspace functional area/domain/action are passed as both the
  typed facade fields and the existing Quantum filter fields.
- The typed `AuthzFilterResponse` is normalized back into Helixor Code's
  existing diagnostic envelope: `scope`, `resource`, `principal`, and
  `constraints` carry enough information for portal/admin diagnostics and
  future fail-closed enforcement.
- `/api/v1/admin/authz/context` now proves the active Helixor org and user are
  included in central authz checks.

The first protected surface remains deliberately narrow: workspace design reads
can run central authz in `diagnostic` or `enforce` mode, and local org scoping
continues to apply even when central authz is disabled.

Verification performed:

- `python -m py_compile src/helixor_code_backend/central_authz.py src/helixor_code_backend/app.py`
- `pytest tests/test_app.py -q -k "central_authz or authz_context or quantum_auth_accepts_access_token_shape or dynamo_login_uses_username_identity_for_org_switch or signup_provisions_dynamo_user_and_workspace_org"`

## Docker Runtime Slice

The local Docker harness has now been exercised against a running local MongoDB
without mounting the source checkout into the container.

Runtime configuration updates:

- `deploy/local-docker/compose.yml` keeps MongoDB external by default through
  `MONGODB_CONNECTION_STRING=mongodb://host.docker.internal:27017`.
- JWT secret file paths are configurable through
  `QUANTUM_JWT_PRIVATE_KEY_FILE` and `QUANTUM_JWT_PUBLIC_KEY_FILE`, defaulting
  to the framework `quantum-default-keys/target/classes` files in this checkout.
- `deploy/local-docker/.env` is ignored, while `.env.example` documents the
  required local settings.

Runtime verification performed:

- `mvn -pl quantum-auth-service package -DskipTests`
- `docker compose --env-file deploy/local-docker/.env -f deploy/local-docker/compose.yml up --build -d`
- `curl http://127.0.0.1:8180/q/health/live` returned `UP`.
- `curl http://127.0.0.1:8180/auth/providers` returned the configured and
  discovered provider chain `custom,oidc`.
- Runtime `/q/openapi` exposes `/auth/providers`, `/v1/authz/filter`,
  `AuthProvidersResponse`, and `AuthzFilterResponse`.
- `POST /auth/login?provider=custom&userId=local-test&password=test123456`
  returned an access token and refresh token from the custom provider.
- `GET /v1/authz/filter` with the token, `orgRefName=HelixorAI`,
  `tenantId=HelixorAI`, `ownerId=local-test`, and no `X-Realm` returned
  `decision=ALLOW`, a Quantum filter, data-domain fields, and principal context.
- Helixor Code's Python bridge successfully called the Dockerized service
  through the generated `quantum_auth_service` SDK and normalized the response
  to `source=generated_sdk`, `decision=ALLOW`, `orgRefs=HelixorAI`, and the
  Quantum filter.

Important finding:

- Sending `X-Realm: quantum-auth` to protected routes returned a 500 because the
  security filter treats `X-Realm` as an explicit realm override and rejects it
  when that realm is not present in the realm collection. Helixor Code now only
  forwards a default realm override when
  `HELIXOR_CODE_AUTHZ_FORWARD_DEFAULT_REALM=true`; otherwise `X-Realm` remains
  optional and absent by default.

## Open Questions

- Should the deployable artifact stay named `quantum-auth-service`, or should it
  become `quantum-control-plane-service`?
- Should Helixor use `POST /auth/login` or the richer `POST /security/login` as
  the canonical login endpoint?
- Does a distinct external OIDC client provider module exist under a different
  name, or do we need to add one for GitHub/Gmail login?
- Do we need a minimal authorization-filter projection facade, or can an
  existing policy/check response be safely typed for SDK consumers?
- Which existing models/resources represent entitlements beyond
  `FeatureFlagResource`?
- Is a direct `AccountResource` needed, or are registration/provisioning flows
  the intended account-management API?
- Should GitHub organization affiliation be modeled as a Quantum membership,
  external identity claim, provider custom property, or all three?
- What is the canonical uniqueness constraint for `userId`, email, subject, and
  external provider subject in the global registry?
- Should Helixor Code enforce central authz on workspace channels, TODOs,
  designs, projects, indexes, companions, and usage in one pass or phase it by
  artifact type?

## Next Implementation Slice

The next slice is the end-to-end/runtime verification pass:

1. Add end-to-end tests for login, registration, create/delete account,
   create/delete/add users, policy CRUD, evaluate/debug endpoints, optional
   `X-Realm`, impersonation, and provider-chain behavior.
2. Run the Helixor Code portal against the Quantum-backed auth mode and verify
   GitHub/provider discovery, org switcher, user management, usage, designs,
   TODOs, indexes, companions, and central authz diagnostics.
3. Decide which additional artifact routes should call central authz in
   diagnostic mode before switching any route to enforce mode.
4. Continue narrowing the published SDK contract to stable external operations
   once the first runtime path is proven.

The most important guardrail remains:

If Quantum already has the model, endpoint, provider, repository, or policy
behavior, the central service must reuse it instead of reimplementing it.
