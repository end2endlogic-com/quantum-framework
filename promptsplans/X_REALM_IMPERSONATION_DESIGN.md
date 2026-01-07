# X-Realm and X-Impersonate Headers: Gap Analysis and Design Document

## Executive Summary

This document analyzes the current implementation of the `X-Impersonate-UserId`, `X-Impersonate-Subject`, and `X-Realm` headers in the Quantum Framework, identifies gaps between the current behavior and the desired behavior, and proposes a comprehensive design to address these gaps.

**Key Finding**: While impersonation (`X-Impersonate-*`) correctly assumes the target user's complete security context (including DataDomain), the `X-Realm` header only changes the target database but does NOT update the DataDomain. This causes data created while using `X-Realm` to be stamped with the caller's original DataDomain rather than a realm-appropriate DataDomain.

---

## 1. Current Implementation Analysis

### 1.1 X-Impersonate-Subject / X-Impersonate-UserId

**Location**: `SecurityFilter.java` - `handleImpersonation()` method

**Current Behavior**:
1. When `X-Impersonate-Subject` or `X-Impersonate-UserId` is provided (only one allowed):
   - The caller's `impersonateFilterScript` is executed to validate permission
   - If authorized, the impersonated user's credential is loaded
   - A new `PrincipalContext` is built with:
     - `defaultRealm` = impersonated user's `DomainContext.defaultRealm`
     - `dataDomain` = impersonated user's `DomainContext.toDataDomain(userId)`
     - `userId` = impersonated user's userId
     - `roles` = impersonated user's roles (merged with caller's SecurityIdentity roles)
     - `impersonatedBySubject` / `impersonatedByUserId` = original caller's identity
     - `dataDomainPolicy` = impersonated user's policy

**Status**: ✅ **Working as intended** - The caller "becomes" the impersonated user from a security perspective.

```java
// From SecurityFilter.buildImpersonatedContext()
private PrincipalContext buildImpersonatedContext(CredentialUserIdPassword targetCreds, 
                                                  CredentialUserIdPassword originalCreds, ...) {
    String[] roles = resolveEffectiveRoles(securityIdentity, targetCreds);
    DataDomain dataDomain = targetCreds.getDomainContext().toDataDomain(targetCreds.getUserId());

    return new PrincipalContext.Builder()
            .withDefaultRealm(targetCreds.getDomainContext().getDefaultRealm())
            .withDataDomain(dataDomain)  // ← Uses impersonated user's DataDomain
            .withUserId(targetCreds.getUserId())
            .withRoles(roles)
            // ...
}
```

### 1.2 X-Realm Header

**Location**: `SecurityFilter.java` - `buildJwtContext()`, `buildIdentityContext()`, `applyRealmOverride()`

**Current Behavior**:
1. When `X-Realm` is provided:
   - The realm is validated against `realmRegEx` or `authorizedRealms` on the caller's credential
   - The `defaultRealm` in PrincipalContext is set to the X-Realm value
   - **CRITICAL GAP**: The `dataDomain` remains the caller's original DomainContext

**Status**: ⚠️ **Partial implementation - DataDomain not updated**

```java
// From SecurityFilter.buildJwtContext()
String effectiveRealm = (realm != null) ? realm : creds.getDomainContext().getDefaultRealm();
return new PrincipalContext.Builder()
        .withDefaultRealm(effectiveRealm)  // ← Realm is updated
        .withDataDomain(dataDomain)         // ← DataDomain NOT updated (uses caller's)
        // ...

// From SecurityFilter.applyRealmOverride()
private PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
    return context;  // ← Currently a no-op!
}
```

### 1.3 Database Selection via Realm

**Location**: `MorphiaDataStoreWrapper.java`, `RuleContext.getRealmId()`

**Current Behavior**:
1. `RuleContext.getRealmId()` returns `principalContext.getDefaultRealm()`
2. `MorphiaDataStoreWrapper.getDataStore(realm)` creates/returns a Morphia datastore for that realm
3. The realm effectively maps 1:1 to a MongoDB database name

```java
// From RuleContext.java
public String getRealmId(PrincipalContext principalContext, ResourceContext resourceContext) {
    if (principalContext != null)
        return principalContext.getDefaultRealm();  // ← Uses defaultRealm for database
    else
        return defaultRealm;
}
```

### 1.4 Data Domain Usage

**Location**: `DataDomainResolver`, `ValidationInterceptor`, `MorphiaRepo`

**Current Behavior**:
1. New entities get their `dataDomain` stamped based on:
   - Entity's existing dataDomain (if present)
   - `DataDomainResolver.resolveForCreate()` which uses:
     - Principal's attached `DataDomainPolicy`
     - Global `DataDomainPolicy`
     - Falls back to `SecurityContext.getPrincipalDataDomain()`
2. Query filtering uses `dataDomain` fields from `PrincipalContext` for scoping

---

## 2. Problem Statement

### Scenario: System Admin Using X-Realm

**User**: System administrator with:
- `userId`: `admin@system.com`
- `defaultRealm`: `system-com`
- `dataDomain`: `{orgRefName: "SYSTEM", tenantId: "system-com", accountNum: "SYSTEM", ownerId: "admin@system.com"}`
- `realmRegEx`: `*` (allowed to access any realm)

**Action**: User sets `X-Realm: mycompanyxyz-com` and creates a new entity

**Expected Behavior**: Entity is created with:
- Database: `mycompanyxyz-com` ✅
- `dataDomain.orgRefName`: Should match `mycompanyxyz-com`'s default org
- `dataDomain.tenantId`: Should match `mycompanyxyz-com`'s default tenant
- `dataDomain.accountNum`: Should match `mycompanyxyz-com`'s default account

**Actual Behavior**: Entity is created with:
- Database: `mycompanyxyz-com` ✅
- `dataDomain.orgRefName`: `"SYSTEM"` ❌
- `dataDomain.tenantId`: `"system-com"` ❌
- `dataDomain.accountNum`: `"SYSTEM"` ❌

**Impact**: The entity is stored in the wrong database partition's DataDomain, breaking tenant isolation and data scoping.

---

## 3. Desired Behavior

### 3.1 X-Impersonate-* (Already Correct)

When using `X-Impersonate-Subject` or `X-Impersonate-UserId`:
- You **become** that identity from a security perspective
- You're limited to what that identity can do
- Filters on data are created based upon the identity you're impersonating
- DataDomain is set from the impersonated user's credential
- The realm/database is the impersonated user's default realm

### 3.2 X-Realm (Needs Enhancement)

When using `X-Realm`:
- You remain **yourself** (your own identity, roles, permissions)
- You act within the **target realm** (different database)
- **NEW**: Your `dataDomain` should be set to the **default DataDomain for that realm**
- Created data should have the target realm's default DataDomain
- Query filters should use the target realm's default DataDomain

### 3.3 Default Data Domain for Realm Concept

Each `Realm` already has a `DomainContext` field which includes:
- `tenantId`
- `defaultRealm`
- `orgRefName`
- `accountId`
- `dataSegment`

This `DomainContext` should be used as the **default DataDomain** when switching realms via `X-Realm`.

---

## 4. Current Data Model

### 4.1 Realm Entity

```java
@Entity
public class Realm extends BaseModel {
    protected String emailDomain;
    protected String connectionString;
    protected String databaseName;  // Maps to realm/database name
    protected String defaultAdminUserId;
    @Valid @NotNull @NonNull
    protected DomainContext domainContext;  // ← This is the "default data domain"
}
```

### 4.2 DomainContext

```java
public class DomainContext {
    protected String tenantId;
    protected String defaultRealm;
    protected String orgRefName;
    protected String accountId;
    protected int dataSegment;
    
    public DataDomain toDataDomain(String ownerId) {
        return DataDomain.builder()
            .tenantId(tenantId)
            .orgRefName(orgRefName)
            .accountNum(accountId)
            .ownerId(ownerId)
            .dataSegment(dataSegment)
            .build();
    }
}
```

### 4.3 PrincipalContext

```java
public final class PrincipalContext {
    String defaultRealm;    // The realm this principal operates in
    DataDomain dataDomain;  // The data domain for scoping
    String userId;
    String[] roles;
    String scope;
    String impersonatedBySubject;
    String impersonatedByUserId;
    // ...
}
```

---

## 5. Proposed Design

### 5.1 Core Concept: Effective DataDomain with Realm Override

When `X-Realm` is set, the `PrincipalContext.dataDomain` should be derived from the **target realm's DomainContext**, not the caller's credential.

### 5.2 Implementation Changes

#### 5.2.1 SecurityFilter.applyRealmOverride() Enhancement

**Current** (no-op):
```java
private PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
    return context;
}
```

**Proposed**:
```java
private PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
    if (realm == null || realm.equals(context.getDefaultRealm())) {
        return context;  // No realm override or same realm
    }
    
    // Realm override requested - look up the target realm's DomainContext
    Optional<Realm> targetRealmOpt = realmRepo.findByRefName(realm, true);
    if (targetRealmOpt.isEmpty()) {
        // Realm validated earlier, this shouldn't happen
        Log.warnf("Realm %s not found during override, using original context", realm);
        return context;
    }
    
    Realm targetRealm = targetRealmOpt.get();
    DomainContext targetDomainContext = targetRealm.getDomainContext();
    
    // Create a new DataDomain using the target realm's context but keeping caller's userId as owner
    DataDomain effectiveDataDomain = targetDomainContext.toDataDomain(context.getUserId());
    
    // Rebuild context with updated realm and DataDomain
    return new PrincipalContext.Builder()
            .withDefaultRealm(realm)
            .withDataDomain(effectiveDataDomain)
            .withUserId(context.getUserId())
            .withRoles(context.getRoles())
            .withScope(context.getScope())
            .withArea2RealmOverrides(context.getArea2RealmOverrides())
            .withDataDomainPolicy(context.getDataDomainPolicy())
            .withImpersonatedBySubject(context.getImpersonatedBySubject())
            .withImpersonatedByUserId(context.getImpersonatedByUserId())
            .withActingOnBehalfOfSubject(context.getActingOnBehalfOfSubject())
            .withActingOnBehalfOfUserId(context.getActingOnBehalfOfUserId())
            // NEW: Track that realm was overridden
            .withRealmOverrideActive(true)
            .withOriginalDataDomain(context.getDataDomain())
            .build();
}
```

#### 5.2.2 PrincipalContext Enhancement

Add fields to track realm override state:

```java
public final class PrincipalContext {
    // Existing fields...
    
    // NEW: Realm override tracking
    boolean realmOverrideActive;       // True if X-Realm override is in effect
    DataDomain originalDataDomain;     // The caller's original DataDomain (for audit)
    
    // Builder additions...
    public Builder withRealmOverrideActive(boolean active) {
        this.realmOverrideActive = active;
        return this;
    }
    
    public Builder withOriginalDataDomain(DataDomain original) {
        this.originalDataDomain = original;
        return this;
    }
}
```

#### 5.2.3 RealmRepo Enhancement

Add method to find realm by refName:

```java
public Optional<Realm> findByRefName(String refName, boolean ignoreRules) {
    List<Filter> filters = new ArrayList<>();
    filters.add(Filters.eq("refName", refName));
    Filter[] qfilters;
    if (!ignoreRules) {
        qfilters = getFilterArray(filters, getPersistentClass());
    } else {
        qfilters = filters.toArray(new Filter[0]);
    }
    
    Query<Realm> query = morphiaDataStoreWrapper
        .getDataStore(getSecurityContextRealmId())
        .find(getPersistentClass())
        .filter(qfilters);
    return Optional.ofNullable(query.first());
}
```

### 5.3 Data Flow Comparison

#### Before (Current - Impersonation)
```
Request: GET /api/orders
Headers: Authorization: Bearer <token>, X-Impersonate-UserId: john@mycompany.com

1. SecurityFilter validates impersonation permission
2. Load john@mycompany.com's credential
3. Build PrincipalContext:
   - defaultRealm = "mycompanyxyz-com" (from john's DomainContext)
   - dataDomain = {org: "MYCOMPANY", tenant: "mycompanyxyz-com", ...} (from john's DomainContext)
   - userId = "john@mycompany.com"
   - roles = john's roles + caller's IdP roles
4. Database: mycompanyxyz-com ✅
5. Data filtering: Uses john's DataDomain ✅
```

#### Before (Current - X-Realm) - BROKEN
```
Request: GET /api/orders
Headers: Authorization: Bearer <token>, X-Realm: mycompanyxyz-com

1. SecurityFilter validates realm access (realmRegEx)
2. Build PrincipalContext:
   - defaultRealm = "mycompanyxyz-com" (from X-Realm) ✅
   - dataDomain = {org: "SYSTEM", tenant: "system-com", ...} (from caller's credential) ❌
   - userId = "admin@system.com" (caller)
   - roles = caller's roles
3. Database: mycompanyxyz-com ✅
4. Data filtering: Uses SYSTEM DataDomain ❌
5. Created records: Stamped with SYSTEM DataDomain ❌
```

#### After (Proposed - X-Realm)
```
Request: GET /api/orders
Headers: Authorization: Bearer <token>, X-Realm: mycompanyxyz-com

1. SecurityFilter validates realm access (realmRegEx)
2. Look up realm "mycompanyxyz-com" → get its DomainContext
3. Build PrincipalContext:
   - defaultRealm = "mycompanyxyz-com" (from X-Realm) ✅
   - dataDomain = {org: "MYCOMPANY", tenant: "mycompanyxyz-com", ...} (from realm's DomainContext) ✅
   - userId = "admin@system.com" (caller)
   - roles = caller's roles
   - realmOverrideActive = true
   - originalDataDomain = {org: "SYSTEM", tenant: "system-com", ...}
4. Database: mycompanyxyz-com ✅
5. Data filtering: Uses MYCOMPANY DataDomain ✅
6. Created records: Stamped with MYCOMPANY DataDomain ✅
```

---

## 6. Security Considerations

### 6.1 Audit Trail

When realm override is active, ensure audit records capture:
- The original caller's identity and DataDomain
- The target realm and effective DataDomain
- That the action was performed via X-Realm override

```java
// In AuditInterceptor or similar
if (principalContext.isRealmOverrideActive()) {
    auditRecord.setRealmOverrideOriginalDataDomain(principalContext.getOriginalDataDomain());
    auditRecord.setRealmOverrideActive(true);
}
```

### 6.2 Permission Verification

The realm override MUST still respect:
- The caller's `realmRegEx` or `authorizedRealms`
- Permission rules that may restrict actions based on the caller's identity
- DataDomainPolicy if the caller has one attached

### 6.3 Impersonation + X-Realm Interaction

Currently, if both impersonation and X-Realm are used:
- Impersonation takes precedence
- The effective realm becomes the impersonated user's realm, not X-Realm

**Recommendation**: This should remain the current behavior, OR reject requests with both headers set.

---

## 7. Implementation Plan

### Phase 1: Core Implementation
1. Add `findByRefName()` method to `RealmRepo`
2. Enhance `PrincipalContext` with realm override tracking fields
3. Implement `applyRealmOverride()` in `SecurityFilter`
4. Add unit tests for realm override DataDomain resolution

### Phase 2: Audit Enhancement
1. Update `AuditInterceptor` to capture realm override information
2. Add audit fields for tracking original vs effective DataDomain

### Phase 3: Documentation & Testing
1. Update permissions.adoc with new behavior
2. Add integration tests for X-Realm with data creation
3. Add tests verifying query filtering uses correct DataDomain

### Phase 4: Edge Cases
1. Handle case where target realm has no DomainContext (fail or fallback?)
2. Test interaction with DataDomainPolicy
3. Test interaction with impersonation headers

---

## 8. Migration Considerations

### 8.1 Backward Compatibility

The change is **additive** and should not break existing behavior:
- If realm's DomainContext is properly configured: New behavior applies
- If realm's DomainContext is null/incomplete: Fall back to current behavior with warning

### 8.2 Realm DomainContext Requirements

All realms that might be accessed via X-Realm MUST have a properly configured `DomainContext`. A migration script should validate this:

```javascript
// MongoDB validation script
db.realm.find({ domainContext: { $exists: false } }).forEach(r => {
    print("WARNING: Realm " + r.refName + " has no domainContext");
});
```

---

## 9. Summary of Changes Required

| Component | Change Type | Description |
|-----------|-------------|-------------|
| `PrincipalContext` | Enhancement | Add `realmOverrideActive`, `originalDataDomain` fields |
| `PrincipalContext.Builder` | Enhancement | Add builder methods for new fields |
| `SecurityFilter.applyRealmOverride()` | Implementation | Look up target realm's DomainContext and rebuild context |
| `RealmRepo` | Enhancement | Add `findByRefName()` method |
| `AuditInterceptor` | Enhancement | Capture realm override audit information |
| Documentation | Update | Update permissions.adoc and related docs |

---

## 10. Open Questions

1. **What if target realm has no DomainContext?**
   - Option A: Fail with 400/500 error
   - Option B: Fall back to caller's DataDomain with warning log
   - **Recommendation**: Option A (fail fast, require proper configuration)

2. **Should X-Realm + X-Impersonate be allowed together?**
   - Current: Impersonation wins, X-Realm ignored
   - Option: Reject with 400 error if both present
   - **Recommendation**: Keep current behavior (impersonation takes precedence)

3. **Should we add X-Realm header to audit logs automatically?**
   - **Recommendation**: Yes, always log X-Realm header value when present

4. **Should there be a configuration to disable the new behavior?**
   - **Recommendation**: No, the fix is the correct behavior and should be default

---

## 11. Phase 5: Permission Filter Variables Enhancement

### 11.1 Problem Statement

When using `X-Realm`, permission rules that filter data may need to dynamically scope by the current realm. Previously, there was no way for a permission filter string to reference the current `defaultRealm` value.

**Example Permission Rule**:
A permission rule might want to filter data where `dataDomain.tenantId` matches the current realm:
```
dataDomain.tenantId:${defaultRealm}
```

Without the `defaultRealm` variable, this filter would fail or need to be hard-coded.

### 11.2 Solution Implemented

The `defaultRealm` variable has been added to the standard variable map used by permission filters.

**Location**: `MorphiaUtils.createStandardVariableMapFrom()`

**Change**:
```java
public static Map<String, String> createStandardVariableMapFrom(PrincipalContext pcontext, ResourceContext rcontext) {
    Map<String, String> variableMap = new HashMap<>();
    variableMap.put("principalId", pcontext.getUserId());
    variableMap.put("pAccountId", pcontext.getDataDomain().getAccountNum());
    variableMap.put("pTenantId", pcontext.getDataDomain().getTenantId());
    variableMap.put("ownerId", pcontext.getDataDomain().getOwnerId());
    variableMap.put("orgRefName", pcontext.getDataDomain().getOrgRefName());
    variableMap.put("resourceId", rcontext.getResourceId());
    variableMap.put("action", rcontext.getAction());
    variableMap.put("functionalDomain", rcontext.getFunctionalDomain());
    variableMap.put("area", rcontext.getArea());
    
    // NEW: Add defaultRealm for dynamic realm-scoped filters
    variableMap.put("defaultRealm", pcontext.getDefaultRealm());
    
    return variableMap;
}
```

### 11.3 Available Filter Variables

The following variables are now available in permission filter strings:

| Variable | Source | Description |
|----------|--------|-------------|
| `${principalId}` | PrincipalContext.userId | The authenticated user's ID |
| `${pAccountId}` | PrincipalContext.dataDomain.accountNum | The principal's account number |
| `${pTenantId}` | PrincipalContext.dataDomain.tenantId | The principal's tenant ID |
| `${ownerId}` | PrincipalContext.dataDomain.ownerId | The data owner ID |
| `${orgRefName}` | PrincipalContext.dataDomain.orgRefName | The organization reference name |
| `${resourceId}` | ResourceContext.resourceId | The target resource ID |
| `${action}` | ResourceContext.action | The action being performed |
| `${functionalDomain}` | ResourceContext.functionalDomain | The functional domain |
| `${area}` | ResourceContext.area | The functional area |
| `${defaultRealm}` | PrincipalContext.defaultRealm | **NEW**: The current realm (changes with X-Realm) |

### 11.4 X-Realm Integration

When `X-Realm` is used:
1. The `PrincipalContext.defaultRealm` is updated to the target realm
2. Permission filters using `${defaultRealm}` automatically use the new realm value
3. This enables permissions to dynamically scope data based on the active realm

**Example**: A permission rule that allows users to see only data in their current realm:
```
dataDomain.tenantId:${defaultRealm}
```

When a system admin uses `X-Realm: mycompanyxyz-com`:
- Without X-Realm: `${defaultRealm}` = `system-com`
- With X-Realm: `${defaultRealm}` = `mycompanyxyz-com`

The filter automatically adapts to the current context.

### 11.5 Tests Added

- `DefaultRealmVariableTest.java` - Comprehensive tests for the `defaultRealm` variable:
  - `defaultRealmIsIncludedInVariableMap` - Verifies variable is present
  - `defaultRealmChangesWithRealm` - Verifies value changes with realm
  - `filterCanUseDefaultRealmVariable` - Tests variable substitution in filters
  - `xRealmScenarioFilterScopesToTargetRealm` - Tests X-Realm integration
  - `filterCanCombineDefaultRealmWithOtherVars` - Tests combined variable usage

---

## Appendix A: Current Code References

### SecurityFilter Key Methods

| Method | Line | Purpose |
|--------|------|---------|
| `determinePrincipalContext()` | 492 | Entry point for building PrincipalContext |
| `buildJwtContext()` | 572 | Handles JWT authentication with realm |
| `buildIdentityContext()` | 535 | Handles SecurityIdentity with realm |
| `handleImpersonation()` | 679 | Processes X-Impersonate headers |
| `buildImpersonatedContext()` | 748 | Builds context for impersonated user |
| `applyRealmOverride()` | 768 | Currently a no-op - needs implementation |
| `validateRealmAccess()` | 658 | Validates X-Realm against realmRegEx |

### Related Files

- `quantum-models/.../PrincipalContext.java` - Principal context model
- `quantum-models/.../DomainContext.java` - Domain context model
- `quantum-models/.../Realm.java` - Realm entity with DomainContext
- `quantum-models/.../DataDomain.java` - Data domain for entity scoping
- `quantum-morphia-repos/.../RealmRepo.java` - Realm repository
- `quantum-framework/.../RuleContext.java` - Rule evaluation and realm resolution

