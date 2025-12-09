# Authorization Mismatch Fix Summary

## Issue
The `/system/permissions/check` API returned ALLOW for `mike.ingardia@movista.com` accessing `people_hub/location/list`, but the actual API call to `/people_hub/location/list` returned DENY (403 Forbidden).

## Root Cause
**Action case normalization mismatch** between the SecurityFilter and the PermissionResource check API.

### SecurityFilter Behavior
**File**: `SecurityFilter.java` lines 218-222

When parsing URL paths like `/people_hub/location/list`, the SecurityFilter automatically converts the action:
```java
if ("list".equalsIgnoreCase(action)) {
    action = "LIST";  // Converts to uppercase
}
```

### Check API Behavior (Before Fix)
**File**: `PermissionResource.java` line ~595

The check API used the action exactly as provided in the request without normalization:
```java
.withAction(req.action != null ? req.action : "*")  // No conversion
```

### The Problem
- When testing with `/system/permissions/check` using `"action": "list"` (lowercase), it checked against rules with lowercase "list"
- When calling the actual API `/people_hub/location/list`, the SecurityFilter converted it to "LIST" (uppercase)
- If the security rule was defined with uppercase "LIST", the check API would return ALLOW (matching lowercase "list" via wildcard), but the actual API would be denied

## Solution Applied

### Fix Location
**File**: `PermissionResource.java` lines 590-597

Added action normalization to match SecurityFilter behavior:

```java
// Normalize action to match SecurityFilter behavior (converts "list" -> "LIST")
String normalizedAction = req.action;
if (normalizedAction != null && "list".equalsIgnoreCase(normalizedAction)) {
   normalizedAction = "LIST";
}

ResourceContext rc = new ResourceContext.Builder()
        .withRealm(realm)
        .withArea(req.area != null ? req.area : "*")
        .withFunctionalDomain(req.functionalDomain != null ? req.functionalDomain : "*")
        .withAction(normalizedAction != null ? normalizedAction : "*")  // Now uses normalized action
        .withResourceId(req.resourceId)
        .withOwnerId(ownerId)
        .build();
```

## Testing Instructions

### 1. Test with Lowercase Action
```bash
curl -X POST http://localhost:8080/system/permissions/check \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "identity": "mike.ingardia@movista.com",
    "area": "people_hub",
    "functionalDomain": "location",
    "action": "list",
    "realm": "system-com",
    "tenantId": "YOUR_TENANT",
    "orgRefName": "YOUR_ORG",
    "accountNumber": "YOUR_ACCOUNT"
  }'
```

### 2. Test with Uppercase Action
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

**Expected**: Both should return the same result now.

### 3. Test Actual API Call
```bash
curl -X GET http://localhost:8080/people_hub/location/list \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "X-Realm: system-com"
```

**Expected**: Should match the result from the check API.

### 4. Login and Test
```bash
# Login as mike.ingardia@movista.com
curl -X POST http://localhost:8080/security/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "mike.ingardia@movista.com",
    "password": "Test1234567890"
  }'

# Use the returned token to test the actual API
curl -X GET http://localhost:8080/people_hub/location/list \
  -H "Authorization: Bearer <TOKEN_FROM_LOGIN>" \
  -H "X-Realm: system-com"
```

## Additional Verification Steps

### Check Role Provenance
Verify the user's roles are being resolved correctly:

```bash
curl -X POST http://localhost:8080/system/permissions/role-provenance \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "userId": "mike.ingardia@movista.com",
    "realm": "system-com"
  }'
```

This will show:
- Roles from credentials
- Roles from user groups
- Roles from token
- Net effective roles

### Enable Debug Logging
Add to `application.properties`:

```properties
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
quarkus.log.category."com.e2eq.framework.securityrules.RuleContext".level=DEBUG
quarkus.log.category."com.e2eq.framework.rest.resources.PermissionResource".level=DEBUG
```

Then check logs for:
- ResourceContext values (area, domain, action)
- PrincipalContext values (userId, roles)
- Matched rules and their effects

### Check Security Rules
Query the database to see what rules exist for this user:

```javascript
// MongoDB query
db.policy.find({
  "principalId": { $in: ["user", "admin", "mike.ingardia@movista.com"] }
}).pretty()
```

Look for rules with:
- `securityURI.header.area: "people_hub"`
- `securityURI.header.functionalDomain: "location"`
- `securityURI.header.action: "LIST"` (or "*")

## Files Modified

1. **PermissionResource.java**
   - Added action normalization in the `/check` endpoint (line ~590)
   - Ensures consistency with SecurityFilter behavior

## Potential Future Improvements

### 1. Centralized Action Normalizer
Create a utility method to normalize all actions consistently:

```java
public class SecurityUtils {
    public static String normalizeAction(String action) {
        if (action == null) return null;
        // Convert common REST actions to uppercase
        switch (action.toLowerCase()) {
            case "list": return "LIST";
            case "view": return "VIEW";
            case "create": return "CREATE";
            case "update": return "UPDATE";
            case "delete": return "DELETE";
            default: return action;
        }
    }
}
```

Then use it in both:
- `SecurityFilter.determineResourceContext()`
- `PermissionResource.check()`

### 2. Unit Tests
Add tests to verify action normalization:

```java
@Test
public void testActionNormalization() {
    // Test lowercase "list" is converted to "LIST"
    CheckRequest req = new CheckRequest();
    req.identity = "test@example.com";
    req.action = "list";
    
    Response response = permissionResource.check(req);
    SecurityCheckResponse checkResp = response.readEntity(SecurityCheckResponse.class);
    
    assertEquals("LIST", checkResp.getResourceContext().getAction());
}
```

### 3. Documentation Update
Update API documentation to clarify:
- Actions are case-insensitive
- Common actions (list, view, create, update, delete) are normalized to uppercase
- Custom actions preserve their case

## Rollback Plan

If this fix causes issues, revert the change:

```bash
cd /Users/mingardia/dev/mrisys/end2endlogic/quantum/framework/quantum-framework
git diff src/main/java/com/e2eq/framework/rest/resources/PermissionResource.java
git checkout src/main/java/com/e2eq/framework/rest/resources/PermissionResource.java
```

## Related Files

- **SecurityFilter.java**: Contains the original action normalization logic
- **PermissionResource.java**: Now includes matching normalization
- **RuleContext.java**: Evaluates rules using the normalized actions
- **ResourceContext.java**: Stores the action value

## Notes

- This fix only normalizes the "list" action currently
- Other actions (view, create, update, delete) may need similar treatment if they exhibit the same issue
- The SecurityFilter has special handling for "list" on line 220, which is why this specific action was problematic
- Consider extending normalization to all standard CRUD actions for consistency
