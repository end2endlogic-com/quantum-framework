# Repository Notes

## Related Repositories

- The framework Cognito provider source is located at `/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework-cognito-provider`.
- The Quantum Enterprise source is located at `/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise`.

## Dependency Boundaries

- The open-source framework must not depend on `quantum-enterprise`.
- `quantum-enterprise` is optional and commercially licensed, while this framework is open source.
- Keep enterprise-specific behavior and integrations outside the framework unless they can remain fully optional and decoupled.

## Save And Update Pattern

- For update flows, read the existing record first and keep the full persisted payload in memory.
- UI edits should modify that in-memory record rather than constructing a partial replacement object by hand.
- Save calls must round-trip the full persisted record. At minimum this includes `id`, `refName`, `dataDomain`, `version`, and `auditInfo`, plus any other persisted fields returned by the read API.
- Create flows should omit `id`; save/update flows should include the existing `id` so the backend performs an update/replace instead of accidentally creating a duplicate record.
- `id` values are Mongo `ObjectId` values and must be preserved exactly as returned by the API.
- The default pattern is: read all, modify the needed fields, then write all.

## Stable auth core and bounded changes

Start with one requested application behavior and an acceptance check. Use
`user-guide/auth.adoc`'s extension ownership table before editing shared auth.
Reuse provider/configuration, session selection, role and policy seams. Add an
abstraction only for a demonstrated gap in that behavior; preserve defaults and
public interfaces. Do not promote adjacent review findings into new work.
Stop when the agreed check, compatibility checks and guidance are complete.

## Auth compatibility and verification

- For OAuth changes, read `quantum-docs/src/docs/asciidoc/user-guide/oauth-server.adoc`
  and `compatibility/auth-provider/README.md` before editing.
- Preserve existing provider method descriptors, overloads, response constructors
  and wire shapes. A security denial change needs an explicit affected-client
  assessment; binary compatibility alone does not prove behavioral compatibility.
- Keep public-client grant and PKCE invariants shared across GET, form POST and
  token exchange. Presentation or application policy must not bypass them.
- Run the released-provider binary probe and OAuth tests, including
  `OAuthHttpValidationTest`. Discovery metadata and replacement refresh tokens
  do not prove complete OIDC, browser SSO or single-use refresh rotation.
- Update these entry points and the documentation of record when behavior changes;
  distinguish implemented, verified, activated and still-planned capabilities.

- For request lifecycle changes, read `quantum-security-runtime/README.md` and
  the request-lifecycle section of `user-guide/auth.adoc`. Keep blocking
  authority calls inside `AuthenticationRequestContext.runBlocking`.
- Request role reuse is internal and delegated-only: exact issuer, subject,
  user, realm, application and token-role inputs, scoped to `RoutingContext`.
  Never turn it into a permission cache or skip provider/admission validation.
  Embedded custom role resolvers remain uncached. Preserve their contracts.
- Run `AuthorizationLifecycleHttpTest` as well as the runtime unit suite;
  verify denied admission and next-request role revocation. Its mocked
  directory/provider seams do not establish production service behavior.

- Optional `SessionSelectionPolicy` is a realm proposal only. Pin both id and
  version in existing session config; omission must retain legacy behavior.
  Reject duplicate/missing/version-mismatched explicit selection at startup.
  Never move identity, role or membership admission into a selection callback.
  Run both workspace-policy HTTP fixtures when evolving this SPI.

- Run `CrossApplicationAHttpTest` and `CrossApplicationBHttpTest` for identity,
  application admission or token changes. Keep their single signed fixture
  unchanged unless the supported contract intentionally changes. Prove valid
  identity can be denied app access and role/grant changes need no token rewrite.
- Shared sign-in is not universal access-token portability. Preserve issuer and
  audience validation, signed `azp` and existing claim compatibility; use local
  request context for effective app/realm and authoritative grants for access.
  Test both origin (`azp` equals app) and receiving-app paths: unchanged-origin
  delegated sessions retain signed roles/admission while the provider accepts
  the token. Receiving-app revocation tests do not prove origin revocation.
- Route JWT reviews through `user-guide/auth.adoc`, the configured provider and
  security runtime first. OAuth/OIDC completion is separate optional scope.
  Server bearer tests do not prove frontend sign-in/handoff or provider revocation.

## Migration ownership

Request migration gates must follow `SystemRealmOwnership`, matching startup. A
remote service must not impose its application schema version on the shared system
realm. Preserve embedded checks and outdated-application rejection in tests. Do not
repair this mismatch by changing identity realm records or stamping database versions.
