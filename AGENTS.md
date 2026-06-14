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
