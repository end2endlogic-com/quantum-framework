# Quantum REST Core

`quantum-rest-core` contains reusable server-side REST infrastructure without
registering a concrete application endpoint. It includes:

- `com.e2eq.framework.rest.core.BaseResource`
- CSV import and export support
- import profiles, processors, calculators, and extension SPIs
- shared query projection conversion
- opt-in, per-JVM HTTP rate limiting

New endpoint modules should depend on this artifact and extend
`com.e2eq.framework.rest.core.BaseResource`.

Applications already extending
`com.e2eq.framework.rest.resources.BaseResource` through `quantum-framework`
remain compatible. The framework keeps that historical type as a bridge to the
new implementation.

This is server-side infrastructure. Cross-service consumers should use SDKs
generated from the producing service's OpenAPI contract rather than depend on
this module.

## Rate limiting

The JAX-RS rate limiter is installed as a pre-matching provider, but defaults
to `OFF`. It therefore has no request-path or bucket-allocation effect until an
application explicitly activates it. The supported modes are:

- `OFF`: bypass rate limiting entirely (the default).
- `MONITOR`: consume tokens and record would-be rejections without blocking.
- `ENFORCE`: reject exhausted clients with HTTP 429, a typed `RestError` JSON
  body, and a whole-second `Retry-After` header.

Configuration is application-manifest/property driven:

| Property | Default | Meaning |
| --- | --- | --- |
| `quantum.rest.rate-limit.mode` | `OFF` | `OFF`, `MONITOR`, or `ENFORCE` |
| `quantum.rest.rate-limit.request.limit` | `rate.limit.request.limit`, then `1000` | Bucket capacity and refill token count |
| `quantum.rest.rate-limit.refill.seconds` | `rate.limit.refill.seconds`, then `5` | Greedy refill period |
| `quantum.rest.rate-limit.max-tracked-clients` | `10000` | Strict upper bound for individually tracked client buckets |
| `quantum.rest.rate-limit.overflow-shards` | `64` | Bounded number of shared fallback buckets after the tracking cap is reached |
| `quantum.rest.rate-limit.idle.seconds` | `600` | Idle time before a tracked bucket can be evicted |
| `quantum.rest.rate-limit.forwarded.enabled` | `false` | Allow a trusted `X-Forwarded-For` value to identify a client |
| `quantum.rest.rate-limit.forwarded.trusted-proxy-hops` | `1` | Trusted hops counted from the right side of `X-Forwarded-For`, including the immediate peer |
| `quantum.rest.rate-limit.forwarded.trusted-peers` | none | Required comma-separated IP/CIDR allowlist for direct peers permitted to supply forwarded identity |
| `quantum.rest.rate-limit.forwarded.ipv4-prefix-length` | `24` | Network prefix used to group trusted forwarded IPv4 identities |
| `quantum.rest.rate-limit.forwarded.ipv6-prefix-length` | `64` | Network prefix used to group trusted forwarded IPv6 identities |

The canonical `quantum.rest.rate-limit.*` values take precedence. The two
historical Movista keys, `rate.limit.request.limit` and
`rate.limit.refill.seconds`, remain supported as fallbacks. Invalid mode
values always stop startup. Invalid limiter values stop startup whenever the
mode is `MONITOR` or `ENFORCE`; `OFF` deliberately ignores dormant limiter
values so an upgrade stays inert.

This pre-authentication limiter selects client identity in this order:
explicitly trusted forwarded address, direct peer address, then one shared
anonymous key. It deliberately does not claim user-level governance because it
runs before JAX-RS authentication; user and endpoint policies belong in a
post-authentication policy layer. Forwarded identity is disabled by default.
Enabling it without at least one valid `forwarded.trusted-peers` IP or CIDR
stops startup. The raw header is honored only when the direct peer matches that
allowlist. Enumerate the actual proxy addresses or narrowly scoped proxy CIDRs;
never use a general workload subnet, because every trusted peer is required to
supply a resolvable forwarded chain. The deployment edge must also remove
client-supplied forwarding headers and Quarkus must use the matching
trusted-proxy configuration. Trusted hops are evaluated from right to left so
a client cannot win by prepending a spoofed address. If forwarding is disabled
behind a shared proxy, all clients behind that proxy intentionally share one
peer bucket; active mode emits a startup warning describing that topology risk.
Failed forwarded resolution is also counted rather than silently collapsing
identity.
Port-suffixed IPv4 and IPv4-mapped IPv6 forms are normalized. A malformed or
incomplete forwarded identity from a trusted peer is rejected with a typed
HTTP 400, consumes no shared proxy bucket, and produces a first-occurrence WARN
that reports observed versus configured hop count.

Buckets and counters are local to one JVM. Consequently, an application with
multiple replicas has an effective aggregate allowance of approximately
`replicas x request.limit`. Trusted forwarded identities are grouped by
configurable network prefix, limiting how many buckets a single source network
can mint. Once the tracking cap is reached, new identities are distributed
across a bounded set of overflow shards until idle entries are evicted; the
registry never grows beyond `max-tracked-clients + overflow-shards`.
`RateLimitStats.snapshot()` exposes
dependency-free, bounded-cardinality counters that an application can bridge
to its existing metrics facility. Alert on sustained `overflowAssignments`
(the tracking cap is exhausted and new clients share one bucket) and
`forwardedResolutionFailed` (a trusted peer supplied a forwarded chain that
did not resolve). Size `max-tracked-clients` to the expected distinct-client
network population inside the idle window. Saturation remains attacker
reachable under sufficiently distributed traffic, so the overflow signal is a
security alert, not only a capacity-planning metric.

In multi-tenant deployments this pre-authentication limiter is a coarse
per-source-network guard, not a tenant quota. Tenants sharing an egress network
or the configured forwarded prefix share a bucket. Moving the IPv4 prefix
toward `/32` reduces that collision at the cost of more buckets; true per-tenant
governance belongs in the post-authentication policy layer after realm and
tenant resolution.

When mode is `MONITOR` or `ENFORCE`, the module contributes a runtime OpenAPI
filter that adds the typed `429` `QuantumRateLimitError` response and
`Retry-After` header to every operation, preserving the generated-SDK seam.
OFF deployments do not advertise a response they cannot emit. The JAX-RS
limiter covers the framework security filter and resource routing; it does not
precede Quarkus HTTP-layer authentication, so HTTP-layer auth endpoints also
require edge or Quarkus-layer throttling.
