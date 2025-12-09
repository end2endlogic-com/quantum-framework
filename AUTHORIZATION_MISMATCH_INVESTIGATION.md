# Authorization Mismatch Investigation

## Problem Statement
The `/system/permissions/check` API returns ALLOW for mike.ingardia@movista.com accessing `people_hub/location/list`, but the actual API call to `/people_hub/location/list` returns DENY (403).

## Root Cause Analysis

### 1. Action Case Sensitivity Issue
**Location**: `SecurityFilter.java` lines 218-222

```java
// Pattern 1: /area/domain/action (3 segments) - PRIMARY
if (segmentCount == 3) {
    String area = segments[0];
    String functionalDomain = segments[1];
    String action = segments[2];
    
    // Special handling: if third segment is "list", set action to LIST
    if ("list".equalsIgnoreCase(action)) {
        action = "LIST";  // ← CONVERTS TO UPPERCASE
    }
```

**The SecurityFilter automatically converts `"list"` → `"LIST"` when parsing the URL path.**

### 2. Check API Does NOT Perform This Conversion
**Location**: `PermissionResource.java` lines 595-602

```java
ResourceContext rc = new ResourceContext.Builder()
        .withRealm(realm)
        .withArea(req.area != null ? req.area : "*")
        .withFunctionalDomain(req.functionalDomain != null ? req.functionalDomain : "*")
        .withAction(req.action != null ? req.action : "*")  // ← NO CONVERSION
        .withResourceId(req.resourceId)
        .withOwnerId(ownerId)
        .build();
```

**The check API uses the action exactly as provided in the request.**

## Verification Steps

### Step 1: Check Your Test Request
When calling `/system/permissions/check`, verify the exact JSON payload:

```json
{
  "identity": "mike.ingardia@movista.com",
  "realm": "system-com",
  "area": "people_hub",
  "functionalDomain": "location",
  "action": "list",  // ← Is this lowercase?
  "tenantId": "...",
  "orgRefName": "...",
  "accountNumber": "..."
}
```

**Try changing `"action": "list"` to `"action": "LIST"` and retest.**

### Step 2: Check Your Security Rules
Look at the rules in the database for this user. The rule might be defined as:

```yaml
- name: "location list access"
  securityURI:
    header:
      identity: "user"  # or specific role
      area: "people_hub"
      functionalDomain: "location"
      action: "LIST"  # ← Uppercase in rule definition
```

### Step 3: Enable Debug Logging
Add this to your `application.properties`:

```properties
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
quarkus.log.category."com.e2eq.framework.securityrules.RuleContext".level=DEBUG
```

Then compare the logs:
1. When calling `/system/permissions/check` with `"action": "list"`
2. When calling `/system/permissions/check` with `"action": "LIST"`
3. When calling the actual `/people_hub/location/list` endpoint

### Step 4: Check Role Resolution
The check API uses `identityRoleResolver.resolveRolesForIdentity()` to get roles. Verify:

```bash
curl -X POST http://localhost:8080/system/permissions/role-provenance \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "mike.ingardia@movista.com",
    "realm": "system-com"
  }'
```

This will show you:
- Roles from credentials
- Roles from user groups
- Roles from token (if authenticated)
- Net effective roles

### Step 5: Compare Contexts
Add temporary logging to see the exact contexts being built:

**In SecurityFilter.java** (after line 160):
```java
if (Log.isInfoEnabled()) {
    Log.infof("SecurityFilter ResourceContext: area=%s, domain=%s, action=%s", 
        resourceContext.getArea(), 
        resourceContext.getFunctionalDomain(), 
        resourceContext.getAction());
    Log.infof("SecurityFilter PrincipalContext: userId=%s, roles=%s", 
        principalContext.getUserId(), 
        Arrays.toString(principalContext.getRoles()));
}
```

**In PermissionResource.java** (after line 602):
```java
if (io.quarkus.logging.Log.isInfoEnabled()) {
    io.quarkus.logging.Log.infof("Check API ResourceContext: area=%s, domain=%s, action=%s", 
        rc.getArea(), 
        rc.getFunctionalDomain(), 
        rc.getAction());
    io.quarkus.logging.Log.infof("Check API PrincipalContext: userId=%s, roles=%s", 
        pc.getUserId(), 
        Arrays.toString(pc.getRoles()));
}
```

## Recommended Fixes

### Option 1: Normalize Action in Check API (Recommended)
**File**: `PermissionResource.java` around line 595

```java
// Normalize action to match SecurityFilter behavior
String normalizedAction = req.action;
if (normalizedAction != null && "list".equalsIgnoreCase(normalizedAction)) {
    normalizedAction = "LIST";
}

ResourceContext rc = new ResourceContext.Builder()
        .withRealm(realm)
        .withArea(req.area != null ? req.area : "*")
        .withFunctionalDomain(req.functionalDomain != null ? req.functionalDomain : "*")
        .withAction(normalizedAction != null ? normalizedAction : "*")
        .withResourceId(req.resourceId)
        .withOwnerId(ownerId)
        .build();
```

### Option 2: Create Centralized Action Normalizer
**New file**: `SecurityUtils.java` (or add to existing)

```java
public static String normalizeAction(String action) {
    if (action == null) return null;
    // Convert common REST actions to uppercase
    if ("list".equalsIgnoreCase(action)) return "LIST";
    if ("view".equalsIgnoreCase(action)) return "VIEW";
    if ("create".equalsIgnoreCase(action)) return "CREATE";
    if ("update".equalsIgnoreCase(action)) return "UPDATE";
    if ("delete".equalsIgnoreCase(action)) return "DELETE";
    return action;
}
```

Then use it in both places:
- `SecurityFilter.determineResourceContext()` line 220
- `PermissionResource.check()` line 595

### Option 3: Make Rules Case-Insensitive
**File**: `RuleContext.java` around line 1100

Modify the wildcard matching to be case-insensitive for actions:

```java
// In the checkRules method, before comparing URIs
if (WildCardMatcher.wildcardMatch(
    uri.uriString().toLowerCase(), 
    r.getSecurityURI().uriString().toLowerCase(),
    IOCase.INSENSITIVE)) {
```

**Note**: This is the least recommended as it may have broader implications.

## Testing Checklist

After applying a fix:

- [ ] Test `/system/permissions/check` with `"action": "list"` → should return same as `"LIST"`
- [ ] Test `/system/permissions/check` with `"action": "LIST"` → should match actual API
- [ ] Test actual API call to `/people_hub/location/list` → should match check API
- [ ] Test with different users/roles to ensure no regression
- [ ] Test other actions (view, create, update, delete) for consistency
- [ ] Run existing unit tests: `./mvnw test -Dtest=SecurityFilter*Test`

## Additional Investigation Points

### Check Data Domain Matching
The rule might have filters on:
- `tenantId`
- `accountNumber`
- `orgRefName`
- `ownerId`

Ensure these match between:
1. The check API request
2. The actual user's credential/profile
3. The rule definition

### Check for Postcondition Scripts
Rules might have postcondition scripts that evaluate differently:

```sql
SELECT refName, rules 
FROM policy 
WHERE principalId IN (
  SELECT UNNEST(roles) FROM credential WHERE userId = 'mike.ingardia@movista.com'
)
```

Look for rules with `postconditionScript` that might be failing.

### Check Rule Priority
Multiple rules might match, but different ones win due to priority:

```bash
curl -X POST http://localhost:8080/system/permissions/check \
  -H "Content-Type: application/json" \
  -d '{
    "identity": "mike.ingardia@movista.com",
    "area": "people_hub",
    "functionalDomain": "location",
    "action": "LIST",
    "realm": "system-com"
  }' | jq '.matchedRuleResults'
```

Compare the `matchedRuleResults` array to see which rules are being evaluated.

## Quick Fix Command

If you want to test immediately, try this curl command with uppercase ACTION:

```bash
curl -X POST http://localhost:8080/system/permissions/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "identity": "mike.ingardia@movista.com",
    "area": "people_hub",
    "functionalDomain": "location",
    "action": "LIST",
    "realm": "system-com",
    "tenantId": "YOUR_TENANT",
    "orgRefName": "YOUR_ORG",
    "accountNumber": "YOUR_ACCOUNT"
  }'
```

Compare this result with your actual API call.
