# Pre-Authorization Enforcement in SecurityFilter

## Overview

This document describes the pre-authorization enforcement feature that prevents unauthorized access when endpoints bypass Morphia repository filtering.

## Problem Statement

Some REST endpoints may not go through Morphia repository filtering, which means security rules applied at the database query level are bypassed. This creates a security gap where unauthorized users could access resources if the endpoint doesn't explicitly check permissions.

## Solution

The `SecurityFilter` now performs a pre-authorization check **before** the endpoint executes, ensuring that security policies are enforced even when repository-level filtering is bypassed.

## Implementation

### Configuration Property

```properties
quantum.security.filter.enforcePreAuth=true  # default: true
```

- **`true`** (default): Pre-authorization is enforced for all non-@PermitAll endpoints
- **`false`**: Pre-authorization is disabled (for legacy flows or gradual migration)

### How It Works

1. **Context Setup**: SecurityFilter determines PrincipalContext and ResourceContext as usual
2. **@PermitAll Check**: If endpoint is annotated with @PermitAll, skip pre-auth
3. **Rule Evaluation**: Call `ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY)`
4. **Decision Enforcement**:
   - If `finalEffect == ALLOW`: Request proceeds to endpoint
   - If `finalEffect != ALLOW`: Request is aborted with HTTP 403 Forbidden

### Code Flow

```java
@Override
public void filter(ContainerRequestContext requestContext) throws IOException {
    // ... determine contexts ...
    
    SecurityContext.setPrincipalContext(principalContext);
    SecurityContext.setResourceContext(resourceContext);
    
    // Pre-authorization check
    if (!isPermitAll && enforcePreAuth) {
        enforcePreAuthorization(principalContext, resourceContext, requestContext);
    }
}

private void enforcePreAuthorization(PrincipalContext principalContext, 
                                     ResourceContext resourceContext,
                                     ContainerRequestContext requestContext) {
    SecurityCheckResponse response = 
        ruleContext.checkRules(principalContext, resourceContext, RuleEffect.DENY);
    
    if (response.getFinalEffect() != RuleEffect.ALLOW) {
        requestContext.abortWith(
            Response.status(403)
                .entity(Map.of(
                    "error", "Access Denied",
                    "message", "...",
                    "decision", response.getDecision(),
                    "scope", response.getDecisionScope()
                ))
                .build()
        );
    }
}
```

## Response Format

When access is denied, the filter returns HTTP 403 with:

```json
{
  "error": "Access Denied",
  "message": "Access denied: user=john@example.com, area=security, domain=policies, action=VIEW",
  "decision": "DENY",
  "scope": "DEFAULT"
}
```

**Fields**:
- `error`: Fixed string "Access Denied"
- `message`: Detailed message with user, area, domain, and action
- `decision`: Rule decision (ALLOW, DENY, or SCOPED)
- `scope`: Decision scope (EXACT, SCOPED, or DEFAULT)

## When Pre-Auth Runs

Pre-authorization runs for:
- ✅ All REST endpoints (unless excluded)
- ✅ Endpoints that bypass Morphia repo filtering
- ✅ Direct resource access endpoints
- ✅ Custom endpoints without explicit permission checks

Pre-authorization is **skipped** for:
- ❌ Endpoints annotated with `@PermitAll`
- ❌ `/hello` health check endpoint
- ❌ When `quantum.security.filter.enforcePreAuth=false`

## Security Policy Integration

Pre-authorization uses the same security rules as repository-level filtering:

```yaml
- name: "User can view policies"
  securityURI:
    header:
      identity: user
      area: security
      functionalDomain: policies
      action: VIEW
    body:
      realm: "*"
      accountNumber: "*"
      tenantId: "*"
  effect: ALLOW
  priority: 100
```

This rule will:
1. Allow access at pre-authorization (filter level)
2. Allow access at repository query level (if applicable)

## Use Cases

### Use Case 1: Direct Resource Access

```java
@GET
@Path("/security/policies/id/{id}")
public Policy getById(@PathParam("id") String id) {
    // No Morphia filtering here - direct lookup
    return policyRepo.findById(id).orElseThrow();
}
```

**Without pre-auth**: Any authenticated user could access any policy by ID
**With pre-auth**: Access is checked against security rules before endpoint executes

### Use Case 2: Custom Aggregation Endpoints

```java
@GET
@Path("/reports/summary")
public ReportSummary getSummary() {
    // Custom aggregation bypassing standard repo filtering
    return customAggregationService.generateSummary();
}
```

**Without pre-auth**: Aggregation might expose data user shouldn't see
**With pre-auth**: Access is validated before aggregation runs

### Use Case 3: Bulk Operations

```java
@POST
@Path("/bulk/update")
public Response bulkUpdate(List<String> ids) {
    // Bulk operation might bypass per-item filtering
    return bulkService.updateMany(ids);
}
```

**Without pre-auth**: Bulk operations might affect unauthorized resources
**With pre-auth**: User must have permission for the bulk action

## Migration Guide

### For New Applications

Enable pre-authorization (default):

```properties
quantum.security.filter.enforcePreAuth=true
```

Ensure all endpoints have appropriate security rules defined.

### For Existing Applications

#### Option 1: Gradual Migration (Recommended)

1. **Start with disabled**:
   ```properties
   quantum.security.filter.enforcePreAuth=false
   ```

2. **Add logging** to identify endpoints that would be blocked:
   ```java
   // Custom filter to log would-be denials
   ```

3. **Fix security rules** for identified endpoints

4. **Enable pre-auth**:
   ```properties
   quantum.security.filter.enforcePreAuth=true
   ```

5. **Monitor** for 403 errors and adjust rules as needed

#### Option 2: Immediate Migration

1. **Enable pre-auth**:
   ```properties
   quantum.security.filter.enforcePreAuth=true
   ```

2. **Test thoroughly** in dev/staging environments

3. **Fix any 403 errors** by adding appropriate security rules

4. **Deploy to production**

### Handling @PermitAll Endpoints

Endpoints that should be publicly accessible must be annotated:

```java
@GET
@Path("/public/info")
@PermitAll  // Bypasses pre-authorization
public Info getPublicInfo() {
    return publicInfoService.getInfo();
}
```

## Performance Considerations

### Impact

Pre-authorization adds a rule evaluation step before each endpoint execution:

- **Typical overhead**: 1-5ms per request
- **With complex rules**: 5-20ms per request
- **With scripted rules**: 10-50ms per request

### Optimization

1. **Rule Priority**: Place most common rules at lower priority numbers (evaluated first)
2. **Final Rules**: Use `finalRule: true` to stop evaluation early
3. **Avoid Scripts**: Minimize use of postcondition scripts in high-traffic rules
4. **Cache Policies**: RuleContext caches loaded policies

### Monitoring

Monitor these metrics:
- Request latency increase after enabling pre-auth
- 403 error rate (should be low after proper configuration)
- Rule evaluation time (available in debug logs)

## Troubleshooting

### Issue: Legitimate requests getting 403

**Symptom**: Users report "Access Denied" for actions they should be able to perform

**Solution**:
1. Check security rules for the area/domain/action combination
2. Verify user has required roles
3. Check rule priority and finalRule settings
4. Review postcondition scripts if present

**Debug**:
```properties
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
```

### Issue: Pre-auth not enforcing

**Symptom**: Unauthorized access still occurring

**Solution**:
1. Verify `quantum.security.filter.enforcePreAuth=true`
2. Check endpoint is not annotated with @PermitAll
3. Ensure RuleContext is properly injected
4. Verify security rules are loaded

### Issue: Performance degradation

**Symptom**: Slow response times after enabling pre-auth

**Solution**:
1. Review rule complexity and count
2. Optimize rule priority ordering
3. Remove unnecessary postcondition scripts
4. Consider caching for frequently accessed resources

## Security Best Practices

1. **Default Deny**: Always use `RuleEffect.DENY` as default
2. **Explicit Allow**: Create explicit ALLOW rules for each action
3. **Least Privilege**: Grant minimum necessary permissions
4. **Regular Audit**: Review security rules periodically
5. **Test Coverage**: Include security tests for all endpoints

## Configuration Examples

### Development Environment

```properties
# Relaxed for development
quantum.security.filter.enforcePreAuth=false
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
```

### Staging Environment

```properties
# Enabled with detailed logging
quantum.security.filter.enforcePreAuth=true
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
```

### Production Environment

```properties
# Enabled with minimal logging
quantum.security.filter.enforcePreAuth=true
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=INFO
```

## Related Features

- **Repository Filtering**: Database-level security filtering in Morphia repos
- **@PermitAll**: Jakarta Security annotation to bypass all checks
- **RuleContext**: Central rule evaluation engine
- **SecurityCheckResponse**: Detailed authorization decision information

## Future Enhancements

Potential improvements:
1. **Audit Logging**: Log all pre-auth decisions for compliance
2. **Rate Limiting**: Integrate with rate limiting based on authorization
3. **Caching**: Cache authorization decisions for repeated requests
4. **Metrics**: Expose pre-auth metrics via Micrometer
5. **Custom Handlers**: Allow custom pre-auth failure handlers

## References

- `SecurityFilter.java` - Main implementation
- `RuleContext.java` - Rule evaluation engine
- `SecurityCheckResponse.java` - Authorization decision model
- `@PermitAll` - Jakarta Security annotation
