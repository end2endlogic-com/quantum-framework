# Quantum REST Core

`quantum-rest-core` contains reusable server-side REST infrastructure without
registering a concrete application endpoint. It includes:

- `com.e2eq.framework.rest.core.BaseResource`
- CSV import and export support
- import profiles, processors, calculators, and extension SPIs
- shared query projection conversion
- opt-in post-authentication REST usage governance and observation

New endpoint modules should depend on this artifact and extend
`com.e2eq.framework.rest.core.BaseResource`.

Applications already extending
`com.e2eq.framework.rest.resources.BaseResource` through `quantum-framework`
remain compatible. The framework keeps that historical type as a bridge to the
new implementation.

This is server-side infrastructure. Cross-service consumers should use SDKs
generated from the producing service's OpenAPI contract rather than depend on
this module.

## REST usage governance

`quantum-rest-core` automatically installs a post-authentication JAX-RS
request/response filter. Its default mode is `OFF`, so an upgrade allocates no
buckets, resolves no policies, and emits no observations until an application
opts in.

The three modes are:

- `OFF`: no request-path effect (default).
- `OBSERVE`: evaluate the policy and consume the local token bucket, record
  `ADMITTED`, `WOULD_REJECT`, `CAPACITY_BYPASSED`, or `ENFORCEMENT_ERROR`, but
  do not block.
- `ENFORCE`: return a typed HTTP 429 plus whole-second `Retry-After` when the
  bucket is exhausted. Missing endpoint, identity, policy, or bounded bucket
  state fails closed with a typed HTTP 503 rather than silently admitting.

The default manifest is property driven:

| Property | Default | Meaning |
| --- | --- | --- |
| `quantum.rest.usage.mode` | `OFF` | `OFF`, `OBSERVE`, or `ENFORCE` |
| `quantum.rest.usage.policy.id` | `default-rest-usage` | Stable policy identity |
| `quantum.rest.usage.policy.version` | `1` | Version included in bucket identity |
| `quantum.rest.usage.policy.request-limit` | `1000` | Bucket capacity and refill token count |
| `quantum.rest.usage.policy.refill-period` | `PT5S` | ISO-8601 full-refill period |
| `quantum.rest.usage.policy.endpoints` | `*` | Comma-separated `area:domain:operationId` selectors or `*` |
| `quantum.rest.usage.allow-unmatched-endpoints` | `true` | Admit a trusted mapped endpoint as `BYPASSED` when no policy matches; `false` returns typed `USAGE_POLICY_NOT_FOUND` 503 |
| `quantum.rest.usage.bucket.max-tracked-keys` | `10000` | Strict per-JVM bound on tenant/subject/endpoint buckets |
| `quantum.rest.usage.bucket.idle-timeout` | `PT10M` | ISO-8601 idle period before opportunistic eviction |
| `mp.openapi.extensions.smallrye.operationIdStrategy` | none | Must be explicitly set to `METHOD` whenever governance is active, keeping generated and runtime operation identities identical |

Applications can replace the `@DefaultBean` `PropertyUsagePolicySource` with a
CDI implementation of `UsagePolicySource`. A source receives a typed
`UsageRequest` and returns an optional, validated `UsagePolicy`. It must not
silently repair or guess policy state; an exception becomes an explicit
enforcement-state result.

`allow-unmatched-endpoints` controls only the determinate “no matching policy”
case. Missing or invalid endpoint identity, trusted principal identity, policy
source state, or bounded bucket state always remains a typed fail-closed error
in `ENFORCE`; the flag cannot turn those indeterminate states into admission.

The reusable policy, admission, identity, observation, and error contracts are
owned by `quantum-contract-core`, which has no Morphia/Mongo dependency.
`quantum-rest-core` is only the runtime consumer (and already depends on
`quantum-morphia-repos` for unrelated REST infrastructure); `quantum-models`
was rejected as a contract home because it directly carries Morphia types.

Endpoint identity is independent of mutable request paths. It combines the
model/resource `@FunctionalMapping` area and domain with the OpenAPI operation
identity. Governed endpoints should declare an explicit
`@Operation(operationId = "...")`; the stable JAX-RS resource method name is
the runtime fallback and requires generated OpenAPI to use the matching
SmallRye `METHOD` operation-ID strategy. Governance activation fails startup
unless that strategy is explicitly configured. Active configuration
validates nonblank, unique OpenAPI operation IDs and verifies configured exact
selectors against the generated contract. Before enabling `ENFORCE`, every
authenticated endpoint in scope must also expose a trusted `@FunctionalMapping`
(directly or through its `BaseResource` model); an unmapped endpoint returns the
typed `USAGE_ENDPOINT_IDENTITY_UNAVAILABLE` 503 instead of being guessed.

Tenant and subject values come only from the framework `PrincipalContext`
established by `SecurityFilter`. The bucket tenant is the effective
`DataDomain.tenantId` after any validated realm override, and the subject is the
provider subject (falling back to the framework user ID). Raw identity
attributes, forwarding headers, and request parameters are not authorities.

Endpoints explicitly annotated `@PermitAll` are excluded from this post-auth
governance filter so login, token, health, and other public routes do not become
503 responses when `ENFORCE` is enabled. A class-level `@PermitAll` does not
override method-level `@Authenticated`, `@RolesAllowed`, or `@DenyAll`.
Public authentication endpoints remain ungoverned by this component; apply a
separately named edge/network limiter before enabling internet exposure. This
filter must not be represented as credential-stuffing or source-IP protection.

Each framework-owned bucket is local to one JVM and is consumed atomically
using an injectable monotonic clock. Keys include policy
identity/version, endpoint, tenant, and subject. The registry never exceeds
`max-tracked-keys`; when full, a new key is admitted without allocating a
bucket and emits `CAPACITY_BYPASSED`. Capacity pressure therefore cannot let
one tenant force unrelated tenants into a 503. Alert on this disposition and
increase capacity or reduce key cardinality. Idle cleanup is opportunistic on
requests—there is no polling, scheduler, background writer, or MongoDB counter
on the admission path. In a multi-replica deployment, the configured allowance
applies independently to each replica unless an application supplies another
admission implementation in a later phase.

Implement the `UsageObserver` CDI SPI to export `UsageObservation` values.
Every active governed response reports endpoint, tenant, subject, HTTP method,
status, latency, request bytes, response bytes, and the typed admission
decision. Unknown byte counts are `-1`. Observer failures are logged after the
response and never rewrite the already-made admission decision. Durable
billing or aggregation belongs behind this SPI and must not introduce a
MongoDB read/modify/write counter into request admission.

Observer implementations are trusted in-process extensions: tenant and subject
identifiers are sensitive. They must not log them unredacted or export them to
an untrusted sink. Consumers that only require cardinality should pseudonymize
the subject before durable storage.

This phase does not enable a pre-authentication network limiter. Edge or
source-network denial-of-service protection is a separate, explicitly named
deployment guard; it must not be presented as tenant or subject usage policy.
