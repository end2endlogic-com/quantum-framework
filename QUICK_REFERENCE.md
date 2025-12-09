# Quick Reference Guide

## LIST Action Inference

### Path Patterns

| Path Pattern | Action | Example |
|-------------|--------|---------|
| `/area/domain/list` | LIST | `/security/policies/list` |
| `/area/domain/list/{id}` | LIST | `/security/policies/list/123` |
| `/area/domain` (GET) | VIEW | `/security/policies` |
| `/area/domain` (POST) | CREATE | `/security/policies` |
| `/area/domain` + `@FunctionalAction("LIST")` | LIST | `/security/policies` |

### Case Insensitivity

All these map to `action=LIST`:
- `/area/domain/list`
- `/area/domain/List`
- `/area/domain/LIST`

## Pre-Authorization

### Configuration

```properties
# Enable (default)
quantum.security.filter.enforcePreAuth=true

# Disable for legacy
quantum.security.filter.enforcePreAuth=false
```

### Behavior

| Condition | Pre-Auth Runs? | Result |
|-----------|---------------|--------|
| `enforcePreAuth=true` + rule ALLOW | Yes | 200 (proceed) |
| `enforcePreAuth=true` + rule DENY | Yes | 403 (abort) |
| `enforcePreAuth=false` | No | Endpoint executes |
| `@PermitAll` endpoint | No | Endpoint executes |

## Security Rules

### Rule Structure

```yaml
- name: "User can list policies"
  securityURI:
    header:
      identity: user
      area: security
      functionalDomain: policies
      action: LIST
    body:
      realm: "*"
      accountNumber: "*"
      tenantId: "*"
  effect: ALLOW
  priority: 10
  finalRule: true
```

### Common Patterns

**Allow LIST**:
```yaml
action: LIST
effect: ALLOW
```

**Allow VIEW**:
```yaml
action: VIEW
effect: ALLOW
```

**Deny all (default)**:
```yaml
# No rule = DENY
```

## Testing

### Run Tests

```bash
# All tests
mvn test -Dtest=SecurityFilter*Test

# Specific test
mvn test -Dtest=SecurityFilterListActionIntegrationTest#testListPathWithAllowedDomain_Returns200
```

### Expected Results

| Test Scenario | Expected |
|--------------|----------|
| GET /area/allowedDomain/list | 200 |
| GET /area/deniedDomain/list | 403 |
| GET /area/allowedDomain | 200 (if VIEW allowed) |
| GET /area/deniedDomain | 403 |

## Troubleshooting

### Issue: Getting 403 for valid requests

**Check**:
1. Rule exists for area/domain/action
2. User has required role
3. Rule priority is correct
4. `finalRule` not blocking

**Debug**:
```properties
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
```

### Issue: LIST not being recognized

**Check**:
1. Path contains "list" segment
2. Or method has `@FunctionalAction("LIST")`
3. Case doesn't matter (list, List, LIST all work)

### Issue: Pre-auth not enforcing

**Check**:
```properties
quantum.security.filter.enforcePreAuth=true
```

## Quick Commands

```bash
# Enable debug logging
export QUARKUS_LOG_CATEGORY_COM_E2EQ_FRAMEWORK_REST_FILTERS_SECURITYFILTER_LEVEL=DEBUG

# Run with pre-auth disabled
mvn quarkus:dev -Dquantum.security.filter.enforcePreAuth=false

# Run specific test
mvn test -Dtest=SecurityFilterListActionIntegrationTest
```

## Key Files

- `SecurityFilter.java` - Main filter implementation
- `RuleContext.java` - Rule evaluation engine
- `SecurityFilterListActionIntegrationTest.java` - Integration tests
- `LIST_ACTION_INFERENCE.md` - Detailed documentation
- `PRE_AUTHORIZATION_ENFORCEMENT.md` - Pre-auth documentation
