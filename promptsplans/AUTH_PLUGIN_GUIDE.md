### Auth Plugin Developer Guide: Populating roleAssignments with RoleSource

This guide explains how to implement role provenance in your Auth Plugin so that login and security-check responses include a structured `roleAssignments` field that indicates where each role came from.

#### What you will deliver
- Login responses that include:
  - `roles`: the union of all roles.
  - `roleAssignments`: a list of `{ role, sources[] }` entries where `sources` is any combination of:
    - `idp` — role asserted by the Identity Provider (JWT/`SecurityIdentity`).
    - `credential` — role configured on the local user record.
    - `usergroup` — role inherited from a user group membership.
- Security check responses (`SecurityCheckResponse`) optionally enriched with `roleAssignments` provenance.

#### Models and types
- `com.e2eq.framework.model.auth.RoleSource` (enum): `USERGROUP`, `IDP`, `CREDENTIAL`
- `com.e2eq.framework.model.auth.RoleAssignment` (record):
  ```java
  public record RoleAssignment(String role, java.util.Set<RoleSource> sources) {}
  ```
- `com.e2eq.framework.model.auth.AuthProvider.LoginPositiveResponse` now includes `List<RoleAssignment> roleAssignments` next to the existing `Set<String> roles`.
- `com.e2eq.framework.model.securityrules.SecurityCheckResponse` includes `List<RoleAssignment> roleAssignments`.

Backward compatibility: If you cannot provide provenance yet, existing constructors auto-fill `roleAssignments` from flat `roles` and mark each role as `CREDENTIAL`.

---

### Integration points for plugins
Implement provenance at two places:
1) In `login(userId, password)` when you return a `LoginPositiveResponse`.
2) In `refreshTokens(refreshToken)` when you return a new `LoginPositiveResponse`.

Optionally enrich security check responses by setting `roleAssignments` after rule evaluation.

---

### Minimal implementation (copy-paste ready)
Add imports:
```java
import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
```

Inside your `login(userId, password)` after you have the `SecurityIdentity identity` and (optionally) the local user record:
```java
// 1) Collect role sets from each source
java.util.Set<String> idpRoles = (identity != null)
    ? new java.util.LinkedHashSet<>(identity.getRoles())
    : java.util.Set.of();
java.util.Set<String> credentialRoles = /* roles from your user record */;
java.util.Set<String> userGroupRoles = /* roles from groups linked to the user profile */;

// 2) Union of all roles for the response
java.util.Set<String> allRoles = new java.util.LinkedHashSet<>();
allRoles.addAll(idpRoles);
allRoles.addAll(credentialRoles);
allRoles.addAll(userGroupRoles);

// 3) Build per-role provenance
java.util.List<RoleAssignment> roleAssignments = allRoles.stream()
    .filter(java.util.Objects::nonNull)
    .map(role -> {
        java.util.EnumSet<RoleSource> src = java.util.EnumSet.noneOf(RoleSource.class);
        if (idpRoles.contains(role)) src.add(RoleSource.IDP);
        if (credentialRoles.contains(role)) src.add(RoleSource.CREDENTIAL);
        if (userGroupRoles.contains(role)) src.add(RoleSource.USERGROUP);
        return new RoleAssignment(role, src);
    })
    .toList();

// 4) Return a positive response using the full constructor
return new com.e2eq.framework.model.auth.AuthProvider.LoginResponse(
    true,
    new com.e2eq.framework.model.auth.AuthProvider.LoginPositiveResponse(
        userId,
        identity,
        allRoles,
        roleAssignments,
        accessToken,
        refreshToken,
        expirationTime,
        mongodbUrl,
        realm
    )
);
```

In `refreshTokens(refreshToken)`, repeat the same pattern. If you cannot reload the local user record or groups, you can return only IDP-based provenance:
```java
java.util.Set<String> idpRoles = new java.util.LinkedHashSet<>(identity.getRoles());
java.util.Set<String> credentialRoles = java.util.Set.of(); // optional if not available
java.util.Set<String> userGroupRoles = java.util.Set.of();  // optional if not available

java.util.Set<String> allRoles = new java.util.LinkedHashSet<>();
allRoles.addAll(idpRoles);
allRoles.addAll(credentialRoles);
allRoles.addAll(userGroupRoles);

java.util.List<RoleAssignment> roleAssignments = allRoles.stream()
    .map(r -> {
        java.util.EnumSet<RoleSource> src = java.util.EnumSet.noneOf(RoleSource.class);
        if (idpRoles.contains(r)) src.add(RoleSource.IDP);
        if (credentialRoles.contains(r)) src.add(RoleSource.CREDENTIAL);
        if (userGroupRoles.contains(r)) src.add(RoleSource.USERGROUP);
        return new RoleAssignment(r, src);
    })
    .toList();
```

---

### Optional: use the central resolver (if available in your runtime)
If your plugin runs within the Quantum framework runtime, you can reuse the resolver:

```java
@jakarta.inject.Inject com.e2eq.framework.security.IdentityRoleResolver identityRoleResolver;

java.util.Map<String, java.util.EnumSet<RoleSource>> provenance =
    identityRoleResolver.resolveRoleSources(identityOrUserId, realm, securityIdentity);
java.util.List<RoleAssignment> roleAssignments = identityRoleResolver.toAssignments(provenance);
java.util.Set<String> allRoles = provenance.keySet();
```

---

### Enriching Security Check responses (optional)
If your plugin participates in building security-check responses, after calling the rule engine:

```java
com.e2eq.framework.model.securityrules.SecurityCheckResponse resp = ruleContext.checkRules(pc, rc);
resp.setRoleAssignments(roleAssignments);
return javax.ws.rs.core.Response.ok(resp).build();
```

---

### JSON examples
Login success response with provenance:
```json
{
  "userId": "alice",
  "roles": ["user", "admin"],
  "roleAssignments": [
    {"role": "user",  "sources": ["idp", "credential"]},
    {"role": "admin", "sources": ["usergroup"]}
  ],
  "accessToken": "...",
  "refreshToken": "...",
  "expirationTime": 1732100000,
  "mongodbUrl": "...",
  "realm": "system"
}
```

Security check response with provenance:
```json
{
  "principalContext": { /* ... */ },
  "resourceContext": { /* ... */ },
  "roleAssignments": [
    {"role": "user",  "sources": ["idp", "credential"]},
    {"role": "admin", "sources": ["usergroup"]}
  ],
  "finalEffect": "ALLOW"
}
```

---

### Edge cases and guidance
- Token-only identities: If you cannot resolve a local user, return `RoleSource.IDP` only.
- Avoid including synthetic roles like `ANONYMOUS` in `roleAssignments`.
- Use `LinkedHashSet` to avoid duplicates and preserve order.
- If a role comes from multiple places, include all relevant sources in its assignment.
- Consider caching user-group lookups for performance.

---

### Test checklist for plugin maintainers
- Unit test: `login()` returns `roleAssignments` with correct sources for typical users.
- Unit test: `refreshTokens()` maintains correct provenance (IDP-only is OK if local data is not loaded).
- Contract test: JSON serialization lists `sources` as lowercase strings (`idp`, `credential`, `usergroup`).
- Negative test: Empty role sets produce an empty `roleAssignments` list (no `ANONYMOUS`).

---

### Migration of existing plugins
1) Add the new imports and build the three role sets.
2) Pass `roleAssignments` using the full `LoginPositiveResponse` constructor.
3) Optionally, enrich `SecurityCheckResponse`.
4) If needed, feature-flag the change and roll out progressively.

---

### Security considerations
- Trust boundaries differ: treat IdP roles as assertions from your SSO and local roles/groups as authoritative in your realm.
- Validate group-to-role mappings if you transform IdP group names to local role names.
- Ensure no privilege escalation when unioning roles from multiple sources.
