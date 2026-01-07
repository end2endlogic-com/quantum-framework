# Test Coverage Summary

## Overview

This document summarizes the test coverage for the LIST action inference and pre-authorization enforcement features.

## Test Files

### 1. SecurityFilterListActionTest.java
**Purpose**: Unit tests for LIST action inference logic

**Coverage**:
- ✅ Three-segment path with "list" → action=LIST
- ✅ Four-segment path with "list" → action=LIST + resourceId
- ✅ Case-insensitive "list" matching
- ✅ Two-segment path infers from HTTP method
- ✅ Non-"list" actions are preserved

### 2. SecurityFilterPreAuthTest.java
**Purpose**: Unit tests for pre-authorization enforcement

**Coverage**:
- ✅ Allow access when rule allows
- ✅ Deny access when rule denies
- ✅ Configurable via property
- ✅ Skip for @PermitAll endpoints

### 3. SecurityFilterListActionIntegrationTest.java
**Purpose**: Integration tests locking in behavior for domain-specific access

**Coverage**:
- ✅ GET /{area}/{domain}/list returns 200 when allowed
- ✅ GET /{area}/{domain}/list returns 403 when not allowed
- ✅ GET /{area}/{domain} (no "list") maps to VIEW
- ✅ GET /{area}/{domain} respects VIEW rules
- ✅ @FunctionalAction("LIST") resolves to LIST without "list" in path
- ✅ GET /{area}/{domain}/list/{id} maps to LIST with resourceId
- ✅ Case-insensitive "list" matching (LIST, list, List)
- ✅ Multiple domains with independent access control
- ✅ Different actions with independent rules

## Test Scenarios

### Scenario 1: User with Access to One Domain

**Setup**:
- User: `testUser`
- Allowed domain: `allowedDomain` (LIST and VIEW permissions)
- Denied domain: `deniedDomain` (no permissions)

**Tests**:

| Request | Expected Action | Expected Result | Test Method |
|---------|----------------|-----------------|-------------|
| GET /testArea/allowedDomain/list | LIST | 200 (ALLOW) | testListPathWithAllowedDomain_Returns200 |
| GET /testArea/deniedDomain/list | LIST | 403 (DENY) | testListPathWithDeniedDomain_Returns403 |
| GET /testArea/allowedDomain | VIEW | 200 (ALLOW) | testNoListSegment_MapsToView |
| GET /testArea/deniedDomain | VIEW | 403 (DENY) | testNoListSegmentDeniedDomain_Returns403 |

### Scenario 2: Annotation-Based Action Resolution

**Setup**:
- Method annotated with `@FunctionalAction("LIST")`
- Path: `/testArea/allowedDomain` (no "list" segment)

**Test**:
- Action resolves to LIST (from annotation, not inferred from GET)
- Rule check uses LIST action
- Result: 200 (ALLOW) if LIST rule exists

**Test Method**: `testFunctionalActionAnnotation_ResolvesToList`

### Scenario 3: LIST with Resource ID

**Setup**:
- Path: `/testArea/allowedDomain/list/123`

**Test**:
- Action: LIST
- ResourceId: 123
- Result: 200 (ALLOW) if LIST rule exists

**Test Method**: `testListWithResourceId_MapsCorrectly`

### Scenario 4: Case Insensitivity

**Setup**:
- Paths: `/testArea/allowedDomain/LIST`, `/testArea/allowedDomain/list`, `/testArea/allowedDomain/List`

**Test**:
- All variations map to action=LIST
- All respect same LIST rules

**Test Method**: `testCaseInsensitiveList_MapsToList`

### Scenario 5: Multiple Domains

**Setup**:
- User has LIST permission on `domain1`
- User has no permission on `domain2`

**Tests**:
- GET /testArea/domain1/list → 200 (ALLOW)
- GET /testArea/domain2/list → 403 (DENY)

**Test Method**: `testMultipleDomains_IndependentAccess`

### Scenario 6: Different Actions

**Setup**:
- User has LIST permission on `allowedDomain`
- User has no CREATE permission on `allowedDomain`

**Tests**:
- GET /testArea/allowedDomain/list → 200 (ALLOW)
- POST /testArea/allowedDomain/create → 403 (DENY)

**Test Method**: `testDifferentActions_IndependentRules`

## Behavior Locked In

### 1. Path-Based Action Inference

✅ **Three-segment paths**:
- `/area/domain/list` → action=LIST
- `/area/domain/view` → action=view (preserved as-is)
- `/area/domain/create` → action=create (preserved as-is)

✅ **Four-segment paths**:
- `/area/domain/list/123` → action=LIST, resourceId=123
- `/area/domain/view/123` → action=view, resourceId=123

✅ **Two-segment paths**:
- `/area/domain` (GET) → action=VIEW (inferred from HTTP method)
- `/area/domain` (POST) → action=CREATE (inferred from HTTP method)
- `/area/domain` with `@FunctionalAction("LIST")` → action=LIST (from annotation)

### 2. Case Insensitivity

✅ All variations map to LIST:
- `list`
- `List`
- `LIST`
- `LiSt`

### 3. Pre-Authorization Enforcement

✅ **When enabled** (`quantum.security.filter.enforcePreAuth=true`):
- Rules checked before endpoint execution
- ALLOW → request proceeds (200)
- DENY → request aborted (403)

✅ **When disabled** (`quantum.security.filter.enforcePreAuth=false`):
- Pre-authorization skipped
- Legacy behavior preserved

✅ **@PermitAll endpoints**:
- Always bypass pre-authorization
- Regardless of `enforcePreAuth` setting

### 4. Domain-Specific Access

✅ **Independent domain rules**:
- Permission on domain1 doesn't grant permission on domain2
- Each domain evaluated separately
- No cross-domain permission leakage

✅ **Independent action rules**:
- LIST permission doesn't grant CREATE permission
- Each action evaluated separately
- No cross-action permission leakage

## Running the Tests

### Unit Tests

```bash
# Run all unit tests
mvn test -Dtest=SecurityFilterListActionTest
mvn test -Dtest=SecurityFilterPreAuthTest

# Run specific test
mvn test -Dtest=SecurityFilterListActionTest#testThreeSegmentPathWithList
```

### Integration Tests

```bash
# Run all integration tests
mvn test -Dtest=SecurityFilterListActionIntegrationTest

# Run specific scenario
mvn test -Dtest=SecurityFilterListActionIntegrationTest#testListPathWithAllowedDomain_Returns200
```

### All Tests

```bash
# Run all security filter tests
mvn test -Dtest=SecurityFilter*Test
```

## Test Data Setup

### Rules Created in Tests

1. **Allow LIST on allowedDomain**:
   ```yaml
   identity: testUser
   area: testArea
   functionalDomain: allowedDomain
   action: LIST
   effect: ALLOW
   priority: 10
   finalRule: true
   ```

2. **Allow VIEW on allowedDomain**:
   ```yaml
   identity: testUser
   area: testArea
   functionalDomain: allowedDomain
   action: VIEW
   effect: ALLOW
   priority: 10
   finalRule: true
   ```

3. **Default DENY** (implicit):
   - No explicit rule for deniedDomain
   - Default effect is DENY
   - Results in 403 for unauthorized access

### Test User Context

```java
PrincipalContext {
    userId: "testUser"
    realm: "test-realm"
    roles: ["user"]
    dataDomain: {
        tenantId: "test-tenant"
        accountNum: "test-account"
        orgRefName: "test-org"
        dataSegment: 0
        ownerId: "testUser"
    }
    scope: "AUTHENTICATED"
}
```

## Expected Outcomes

### Success Cases (200)

| Path | Action | Domain | Reason |
|------|--------|--------|--------|
| /testArea/allowedDomain/list | LIST | allowedDomain | Rule allows LIST |
| /testArea/allowedDomain | VIEW | allowedDomain | Rule allows VIEW |
| /testArea/allowedDomain/list/123 | LIST | allowedDomain | Rule allows LIST |

### Failure Cases (403)

| Path | Action | Domain | Reason |
|------|--------|--------|--------|
| /testArea/deniedDomain/list | LIST | deniedDomain | No rule allows LIST |
| /testArea/deniedDomain | VIEW | deniedDomain | No rule allows VIEW |
| /testArea/allowedDomain/create | CREATE | allowedDomain | No rule allows CREATE |

## Continuous Integration

### Test Execution

Tests should be run:
- ✅ On every commit (pre-commit hook)
- ✅ On every pull request (CI pipeline)
- ✅ Before every release (release pipeline)

### Coverage Requirements

Minimum coverage targets:
- Unit tests: 90%
- Integration tests: 80%
- Overall: 85%

### Failure Handling

If tests fail:
1. Build fails immediately
2. No deployment to any environment
3. Developer notified
4. Fix required before merge

## Maintenance

### Adding New Tests

When adding new features:
1. Add unit tests for new logic
2. Add integration tests for new scenarios
3. Update this document with new coverage
4. Ensure all existing tests still pass

### Updating Tests

When modifying behavior:
1. Update affected tests
2. Add new tests for new behavior
3. Ensure backward compatibility tests pass
4. Document breaking changes

## Related Documentation

- [LIST_ACTION_INFERENCE.md](LIST_ACTION_INFERENCE.md) - Feature documentation
- [PRE_AUTHORIZATION_ENFORCEMENT.md](PRE_AUTHORIZATION_ENFORCEMENT.md) - Pre-auth documentation
- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md) - Overall implementation summary
