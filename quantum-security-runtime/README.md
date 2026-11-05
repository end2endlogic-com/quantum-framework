# Quantum Security Runtime

`quantum-security-runtime` contains the endpoint-free HTTP security runtime:
authorization filters, realm resolution, role augmentation, model mapping,
and field-policy response handling.

Server REST modules select this artifact when they need Quantum request
security. Client applications using generated SDKs should not depend on it.

This artifact does not expose REST endpoints.

## Request lifecycle (1.4.3-SNAPSHOT)

`RolesAugmentor` dispatches repository and membership work through Quarkus's
blocking authentication context. It obtains the request from the authentication
attributes, so the JAX-RS request context need not exist yet. Existing two-argument
augmentation and non-HTTP callers remain supported.

For delegated application sessions, `RequestAuthorizationState` stores successful
role resolutions on the Vert.x `RoutingContext`. `RolesAugmentor` and
`SecurityFilter` reuse a result only for identical issuer, subject, user ID,
effective realm, application ID and token roles. Results are defensively copied;
exceptions are not cached. A later request re-reads role authority. This gives
one request a stable role observation, not immediate mid-request revocation.
Embedded `IdentityRoleResolver` implementations remain uncached because their
custom credential inputs do not yet have a complete versioned cache contract.

```mermaid
sequenceDiagram
    participant JWT as Verified identity
    participant A as RolesAugmentor
    participant R as Request state
    participant D as Role authority
    participant F as SecurityFilter
    JWT->>A: Identity and routing attributes
    A->>D: Resolve on worker
    D-->>R: Successful role result keyed by exact inputs
    A-->>JWT: Augmented identity for coarse role gate
    F->>F: Validate provider and select effective principal
    F->>R: Reuse matching delegated roles (otherwise resolve)
    F->>F: Enforce realm, application and tenant admission
    F->>R: Immutable principal projection
    F->>F: Existing resource permission and data rules
```

`ResolvedAuthorizationContext` is a package-private immutable projection after
principal admission. It is not a permission decision, a public extension SPI,
or a replacement for `PrincipalContext` / `RuleContext`. Custom properties,
policy provenance and full coarse/fine role parity remain outside this slice.
The legacy augmented `user` role behavior is retained. No public signatures,
wire fields or storage shapes change.

With `quantum.auth.trust-token-claims=true`, augmentation and authenticated JWT
migration realm selection avoid local `CredentialRepo` lookups. Migration checks
still run: an explicit `X-Realm` keeps existing precedence; otherwise service
tokens use the system realm, sessions use the configured session realm or signed
realm. Missing required signed realm fails closed. Realm selection never grants
realm access; the authorization filter still admits the request separately.

## Verification

Run `mvn -pl quantum-security-runtime,quantum-oauth-server -am test` from the
repository root, plus the released-provider probe documented under
`compatibility/auth-provider`. `AuthorizationLifecycleHttpTest` uses real signed
JWTs, HTTP routing, augmentation and REST admission with mocked provider,
directory, membership and migration services. It checks worker dispatch, one
matching role read per request, async snapshot access, denied app/tenant access
and revocation on the next request. The PEM files under test resources are
public disposable fixtures only. These tests do not prove production directory
availability, full rule-engine integration, OIDC or refresh rotation.
