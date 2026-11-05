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
