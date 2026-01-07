# LIST Action Inference in SecurityFilter

## Overview

This document describes the enhanced action inference logic in `SecurityFilter.determineResourceContext()` to properly handle LIST semantics for REST endpoints.

## Problem Statement

Previously, paths like `/area/domain/list` and `/area/domain/list/{id}` were not consistently treated as `action=LIST`. The system would either:
- Use the literal path segment "list" as the action (lowercase)
- Infer action from HTTP method (GET → VIEW) for 2-segment paths

This caused inconsistencies in security policy evaluation where LIST-specific rules wouldn't match.

## Solution

The `determineResourceContext()` method now implements special handling for LIST semantics:

### 1. Three-Segment Paths with "list"

**Pattern**: `/area/domain/list`

When the third segment equals "list" (case-insensitive), the action is set to `LIST`:

```java
if ("list".equalsIgnoreCase(action)) {
    action = "LIST";
}
```

**Example**:
- Path: `/security/policies/list`
- Result: `area=security, domain=policies, action=LIST`

### 2. Four-Segment Paths with "list"

**Pattern**: `/area/domain/list/{id}`

When the third segment equals "list" (case-insensitive), the action is set to `LIST` and the fourth segment becomes the `resourceId`:

```java
if ("list".equalsIgnoreCase(action)) {
    action = "LIST";
}
```

**Example**:
- Path: `/security/policies/list/abc123`
- Result: `area=security, domain=policies, action=LIST, resourceId=abc123`

### 3. Two-Segment Paths with @FunctionalAction

**Pattern**: `/area/domain`

For two-segment paths, the system now checks for `@FunctionalAction` annotation before inferring from HTTP method:

```java
String annotatedAction = getAnnotatedAction();
if (annotatedAction != null) {
    action = annotatedAction;
} else {
    action = inferActionFromHttpMethod(requestContext.getMethod());
}
```

**Example with annotation**:
```java
@GET
@Path("/security/policies")
@FunctionalAction("LIST")
public Collection<Policy> getList() { ... }
```
- Path: `/security/policies`
- Result: `area=security, domain=policies, action=LIST` (from annotation)

**Example without annotation**:
- Path: `/security/policies` (GET request)
- Result: `area=security, domain=policies, action=VIEW` (inferred from HTTP method)

## Implementation Details

### New Helper Method

Added `getAnnotatedAction()` to extract action from method-level `@FunctionalAction` annotation:

```java
private String getAnnotatedAction() {
    try {
        if (resourceInfo != null && resourceInfo.getResourceMethod() != null) {
            com.e2eq.framework.annotations.FunctionalAction fa = 
                resourceInfo.getResourceMethod().getAnnotation(
                    com.e2eq.framework.annotations.FunctionalAction.class);
            if (fa != null) {
                return fa.value();
            }
        }
    } catch (Exception ex) {
        Log.debugf("Failed to extract @FunctionalAction annotation: %s", ex.toString());
    }
    return null;
}
```

### Case Insensitivity

The "list" check is case-insensitive, so all of these are treated as `action=LIST`:
- `/area/domain/list`
- `/area/domain/List`
- `/area/domain/LIST`

## Usage Examples

### Example 1: Standard List Endpoint

```java
@Path("/security/policies")
public class PolicyResource {
    
    @GET
    @Path("list")
    public Collection<Policy> getList() {
        // Path: /security/policies/list
        // Action: LIST
    }
}
```

### Example 2: List with ID

```java
@Path("/security/policies")
public class PolicyResource {
    
    @GET
    @Path("list/{id}")
    public Policy getListById(@PathParam("id") String id) {
        // Path: /security/policies/list/abc123
        // Action: LIST
        // ResourceId: abc123
    }
}
```

### Example 3: Using @FunctionalAction Annotation

```java
@Path("/security/policies")
public class PolicyResource {
    
    @GET
    @FunctionalAction("LIST")
    public Collection<Policy> getList() {
        // Path: /security/policies
        // Action: LIST (from annotation, not inferred from GET)
    }
}
```

### Example 4: Without Annotation (Legacy Behavior)

```java
@Path("/security/policies")
public class PolicyResource {
    
    @GET
    public Collection<Policy> getList() {
        // Path: /security/policies
        // Action: VIEW (inferred from GET method)
    }
}
```

## Security Policy Implications

With this change, security policies can now properly target LIST operations:

### Before (Inconsistent)

```yaml
- name: "User can list policies"
  securityURI:
    header:
      identity: user
      area: security
      functionalDomain: policies
      action: VIEW  # Had to use VIEW because GET was inferred
```

### After (Consistent)

```yaml
- name: "User can list policies"
  securityURI:
    header:
      identity: user
      area: security
      functionalDomain: policies
      action: LIST  # Now properly matches LIST action
```

## Precedence Order

The action is determined in the following order:

1. **@FunctionalAction annotation** (highest priority)
   - Only checked for 2-segment paths
   - Explicitly declared action on the method

2. **Path segment "list"** (medium priority)
   - Checked for 3 and 4-segment paths
   - Case-insensitive match

3. **HTTP method inference** (lowest priority)
   - GET → VIEW
   - POST → CREATE
   - PUT/PATCH → UPDATE
   - DELETE → DELETE

## Backward Compatibility

This change maintains backward compatibility:

- Existing 3-segment paths with "list" now correctly map to `action=LIST`
- Existing 2-segment paths without `@FunctionalAction` continue to infer from HTTP method
- Non-"list" actions are preserved as-is
- Case-insensitive matching prevents breaking changes from different casing

## Testing

Test coverage includes:

1. Three-segment path with "list" → action=LIST
2. Four-segment path with "list" → action=LIST + resourceId
3. Case-insensitive "list" matching
4. Two-segment path with @FunctionalAction → uses annotation
5. Two-segment path without annotation → infers from HTTP method
6. Non-"list" actions are preserved

See: `SecurityFilterListActionTest.java`

## Migration Guide

### For New Endpoints

Use one of these patterns:

```java
// Option 1: Explicit path segment
@GET
@Path("list")
public Collection<T> getList() { ... }

// Option 2: Annotation on 2-segment path
@GET
@FunctionalAction("LIST")
public Collection<T> getList() { ... }
```

### For Existing Endpoints

If you have existing endpoints that should be treated as LIST:

1. **Add `/list` path segment**:
   ```java
   @GET
   @Path("list")  // Add this
   public Collection<T> getList() { ... }
   ```

2. **Or add @FunctionalAction annotation**:
   ```java
   @GET
   @FunctionalAction("LIST")  // Add this
   public Collection<T> getList() { ... }
   ```

3. **Update security policies** to use `action: LIST` instead of `action: VIEW`

## Related Files

- `SecurityFilter.java` - Main implementation
- `FunctionalAction.java` - Annotation definition
- `SecurityFilterListActionTest.java` - Test coverage
- `ResourceContext.java` - Context model

## Future Enhancements

Potential improvements:
1. Support for other standard actions (SEARCH, EXPORT, etc.)
2. Configurable action mapping rules
3. Annotation-based action inference for all path patterns
4. Action validation against a registry of allowed actions
