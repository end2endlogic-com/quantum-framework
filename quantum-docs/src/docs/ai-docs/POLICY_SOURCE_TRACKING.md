# Policy Source Tracking

## Overview

This document describes the implementation of policy source tracking in the Quantum Framework. The feature allows tracking where security policies originate from, distinguishing between system default rules and policies stored in the database collection.

## Changes Made

### 1. Policy Model Enhancement

**File**: `quantum-models/src/main/java/com/e2eq/framework/model/security/Policy.java`

Added a transient field `policySource` to track the origin of each policy:

```java
protected transient String policySource;

public String getPolicySource() {
   return policySource;
}

public void setPolicySource(String policySource) {
   this.policySource = policySource;
}
```

**Note**: The field is marked as `transient` so it won't be persisted to the database.

### 2. RuleContext Updates

**File**: `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`

#### a. Database Policy Source Marking

When loading policies from the database in `reloadFromRepo()`, each policy is now marked with source `POLICY_COLLECTION`:

```java
for (com.e2eq.framework.model.security.Policy p : policies) {
   // Mark policy source as database collection
   p.setPolicySource("POLICY_COLLECTION");
   // ... rest of processing
}
```

#### b. Default System Policies Extraction

Added new method `getDefaultSystemPolicies()` to extract system default rules as Policy objects:

```java
public List<com.e2eq.framework.model.security.Policy> getDefaultSystemPolicies() {
   // Returns system default rules as Policy objects
   // Each policy is marked with source "SYSTEM_DEFAULT"
}
```

This method:
- Temporarily rebuilds system rules
- Groups rules by identity (user/role)
- Creates Policy objects for each identity
- Marks each policy with `SYSTEM_DEFAULT` source
- Returns the list without affecting the current rule context

### 3. PolicyResource List API Override

**File**: `quantum-framework/src/main/java/com/e2eq/framework/rest/resources/PolicyResource.java`

Overrode the `getList()` method to merge default system policies with database policies:

```java
@Override
public Collection<Policy> getList(HttpHeaders headers, int skip, int limit, 
                                   String filter, String sort, String projection) {
   // 1. Get default system policies
   List<Policy> defaultPolicies = ruleContext.getDefaultSystemPolicies();
   
   // 2. Apply filtering to default policies using QueryPredicates
   List<Policy> filteredDefaults = applyFilterToPolicies(defaultPolicies, filter);
   
   // 3. Get database policies using parent implementation
   Collection<Policy> dbCollection = super.getList(headers, skip, limit, filter, sort, projection);
   
   // 4. Merge: default policies first, then database policies
   List<Policy> mergedList = new ArrayList<>();
   mergedList.addAll(filteredDefaults);
   mergedList.addAll(dbCollection.getItems());
   
   // 5. Return merged collection with adjusted count
   return new Collection<>(mergedList, skip, limit, filter, totalCount);
}
```

Added helper method `applyFilterToPolicies()` to filter default policies using QueryPredicates:

```java
private List<Policy> applyFilterToPolicies(List<Policy> policies, String filter) {
   // Uses QueryPredicates.compilePredicate() via reflection
   // Applies filter to each policy as JsonNode
   // Returns filtered list
}
```

## Policy Source Values

The system now uses two distinct source values:

1. **`SYSTEM_DEFAULT`**: Policies created from default system rules in `RuleContext.addSystemRules()`
   - These include rules for: system user, system role, user role, admin role, anonymous user
   - Always present and cannot be deleted
   - Appear first in list API results

2. **`POLICY_COLLECTION`**: Policies loaded from the MongoDB policy collection
   - Created via API or seed data
   - Can be modified and deleted
   - Appear after system default policies in list API results

## API Behavior

### GET /security/permission/policies/list

The list endpoint now returns a merged view:

1. **Default System Policies** (marked with `policySource: "SYSTEM_DEFAULT"`)
   - Filtered using QueryPredicates if filter parameter is provided
   - Always included in results

2. **Database Policies** (marked with `policySource: "POLICY_COLLECTION"`)
   - Filtered using standard Morphia query filters
   - Retrieved from policy collection

**Response Structure**:
```json
{
  "items": [
    {
      "refName": "system-default-system",
      "principalId": "system",
      "policySource": "SYSTEM_DEFAULT",
      "rules": [...]
    },
    {
      "refName": "system-default-user",
      "principalId": "user",
      "policySource": "SYSTEM_DEFAULT",
      "rules": [...]
    },
    {
      "refName": "custom-policy",
      "principalId": "customRole",
      "policySource": "POLICY_COLLECTION",
      "rules": [...]
    }
  ],
  "totalCount": 3,
  "skip": 0,
  "limit": 50
}
```

## Filter Support

The implementation supports filtering on both default and database policies:

- **Default Policies**: Filtered in-memory using `QueryPredicates.compilePredicate()`
- **Database Policies**: Filtered at database level using Morphia filters

Example filter queries:
```
?filter=principalId:user
?filter=policySource:SYSTEM_DEFAULT
?filter=refName~custom
```

## Benefits

1. **Transparency**: Users can now see where each policy originates
2. **Unified View**: Single API endpoint shows both system defaults and custom policies
3. **Filtering**: Both types of policies can be filtered using the same query syntax
4. **Audit Trail**: Clear distinction between built-in and custom policies

## Future Enhancements

Potential improvements:
1. Add `policySource` to Policy entity as a persisted field with default value
2. Support additional source types (e.g., `SEED_DATA`, `IMPORTED`, `CLONED`)
3. Add filtering by source in the UI
4. Provide separate endpoints for system vs. custom policies if needed

## Testing

To test the implementation:

1. **List all policies**:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" \
        -H "X-Realm: demo" \
        http://localhost:8080/security/permission/policies/list
   ```

2. **Filter by source**:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" \
        -H "X-Realm: demo" \
        "http://localhost:8080/security/permission/policies/list?filter=policySource:SYSTEM_DEFAULT"
   ```

3. **Filter by principal**:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" \
        -H "X-Realm: demo" \
        "http://localhost:8080/security/permission/policies/list?filter=principalId:user"
   ```

## Notes

- The `policySource` field is transient and not persisted to the database
- System default policies are regenerated on each list request
- The merge happens at the API layer, not in the repository
- Pagination parameters (skip/limit) apply to the merged result set
